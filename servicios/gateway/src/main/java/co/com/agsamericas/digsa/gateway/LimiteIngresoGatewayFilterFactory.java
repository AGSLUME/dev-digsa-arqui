package co.com.agsamericas.digsa.gateway;

import java.net.InetSocketAddress;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Filtro {@code LimiteIngreso} (ADR-003; PENDIENTES_I1, bloqueante 4) para {@code /api/auth/**}: límite de tasa en Redis por IP del
 * cliente y, en {@code ingreso} y {@code olvide}, también por usuario (el {@code usuario} o el {@code valor} del cuerpo, leído con un tope
 * de {@link #MAXIMO_CUERPO} bytes). Ventanas fijas de un minuto con claves {@code lume:limite:*} (las únicas que la ACL de Redis permite).
 * Al pasarse: 429 en RFC 7807. Es una defensa adicional: el bloqueo 5/15 por usuario y el tope de códigos por hora siguen en
 * {@code seguridad}.
 *
 * <p>Si Redis no responde, deja pasar (falla abierto) y lo registra: el límite de tasa no debe impedir ingresar, y {@code seguridad}
 * mantiene sus propios topes.
 */
@Component
class LimiteIngresoGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    static final int MAXIMO_CUERPO = 4096;
    static final String DEMASIADAS = "Demasiados intentos seguidos. Espere un minuto e intente de nuevo.";
    private static final Set<String> CON_USUARIO = Set.of("/api/auth/ingreso", "/api/auth/olvide");
    private static final Set<String> CON_SESION = Set.of("/api/auth/codigo", "/api/auth/codigo/reenviar", "/api/auth/cambiar-clave");
    private static final Duration VENTANA = Duration.ofMinutes(1);
    private static final Logger LOG = LoggerFactory.getLogger(LimiteIngresoGatewayFilterFactory.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReactiveStringRedisTemplate redis;
    private final Clock reloj;
    private final int porIp;
    private final int porUsuario;

    LimiteIngresoGatewayFilterFactory(ReactiveStringRedisTemplate redis, Clock reloj,
                                      @Value("${lume.limite.ip-por-minuto:60}") int porIp,
                                      @Value("${lume.limite.usuario-por-minuto:10}") int porUsuario) {
        super(Object.class);
        this.redis = redis;
        this.reloj = reloj;
        this.porIp = porIp;
        this.porUsuario = porUsuario;
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            String ip = ip(exchange);
            String ruta = exchange.getRequest().getPath().pathWithinApplication().value();
            // Una ruta codificada (/api/auth/%69ngreso) la decodifica el servicio pero no casaría con las rutas de abajo: no se admite
            // (revisión de seguridad, M3)
            if (ruta.indexOf('%') >= 0) {
                return ConfiguracionSeguridadGateway.responder(exchange.getResponse(), 400, "La petición no tiene el formato esperado.", null);
            }
            long ventana = reloj.millis() / VENTANA.toMillis();
            Mono<Boolean> porIpOk = dentro("ip:" + ip + ":" + ventana, porIp);
            boolean post = exchange.getRequest().getMethod() == HttpMethod.POST;
            if (post && CON_SESION.contains(ruta)) {
                // Código, reenvío y cambio de clave: además, por sesión de ingreso (cookie del emisor), o por IP si no la trae (M1)
                String clave = exchange.getRequest().getCookies().getOrDefault(SoloCookieEmisorGatewayFilterFactory.COOKIE, List.of())
                        .stream().findFirst().map(c -> "sesion:" + huella(c.getValue())).orElse("sinsesion:" + ip);
                return porIpOk.flatMap(ok -> ok ? dentro(clave + ":" + ventana, porUsuario) : Mono.just(false))
                        .flatMap(ok -> ok ? chain.filter(exchange) : demasiadas(exchange));
            }
            if (!post || !CON_USUARIO.contains(ruta)) {
                return porIpOk.flatMap(ok -> ok ? chain.filter(exchange) : demasiadas(exchange));
            }
            return porIpOk.flatMap(ok -> {
                if (!ok) {
                    return demasiadas(exchange);
                }
                return DataBufferUtils.join(exchange.getRequest().getBody(), MAXIMO_CUERPO)
                        .map(LimiteIngresoGatewayFilterFactory::leer)
                        .defaultIfEmpty(new byte[0])
                        .flatMap(cuerpo -> {
                            ServerWebExchange conCuerpo = exchange.mutate().request(reponer(exchange.getRequest(), cuerpo)).build();
                            // Sin un usuario legible (JSON raro que el servicio sí podría leer): el mismo límite, por IP (M3)
                            String clave = usuario(cuerpo).map(u -> "usuario:" + u).orElse("sinusuario:" + ip);
                            return dentro(clave + ":" + ventana, porUsuario)
                                    .flatMap(bien -> bien ? chain.filter(conCuerpo) : demasiadas(conCuerpo));
                        })
                        // Cuerpo de más de 4 KB: no es un ingreso legítimo
                        .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class,
                                e -> ConfiguracionSeguridadGateway.responder(exchange.getResponse(), 400,
                                        "La petición no tiene el formato esperado.", null));
            });
        };
    }

    /** Suma uno a la ventana; ¿sigue dentro del límite? Si Redis falla, deja pasar. */
    private Mono<Boolean> dentro(String clave, int limite) {
        String k = "lume:limite:" + clave;
        return redis.opsForValue().increment(k)
                .flatMap(n -> n == 1 ? redis.expire(k, VENTANA.plusSeconds(5)).thenReturn(n) : Mono.just(n))
                .map(n -> n <= limite)
                .onErrorResume(e -> {
                    LOG.warn("[limite] Redis no respondió: {}", e.getClass().getSimpleName());
                    return Mono.just(true);
                });
    }

    private static Mono<Void> demasiadas(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set("Retry-After", "60");
        return ConfiguracionSeguridadGateway.responder(exchange.getResponse(), 429, DEMASIADAS, "demasiadas_peticiones");
    }

    /** El usuario escrito, normalizado como lo hace seguridad (minúsculas, sin espacios en los extremos). */
    static Optional<String> usuario(byte[] cuerpo) {
        if (cuerpo.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode j = JSON.readTree(cuerpo);
            JsonNode v = j.has("usuario") ? j.get("usuario") : j.get("valor");
            if (v == null || !v.isString() || v.asString().isBlank()) {
                return Optional.empty();
            }
            String u = v.asString().strip().toLowerCase(Locale.ROOT);
            return Optional.of(u.length() > 150 ? u.substring(0, 150) : u);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Huella corta de la cookie de sesión del emisor para la clave de Redis (nunca el valor en claro). */
    static String huella(String valor) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** La IP del cliente: la que ve el gateway (X-Forwarded-For lo fija él, ADR-003; los proxies confiables los resuelve Netty). */
    private static String ip(ServerWebExchange exchange) {
        InetSocketAddress a = exchange.getRequest().getRemoteAddress();
        return a == null || a.getAddress() == null ? "desconocida" : a.getAddress().getHostAddress();
    }

    private static byte[] leer(DataBuffer b) {
        byte[] r = new byte[b.readableByteCount()];
        b.read(r);
        DataBufferUtils.release(b);
        return r;
    }

    /** La petición con el cuerpo ya leído, para reenviarlo tal cual a seguridad. */
    private static ServerHttpRequest reponer(ServerHttpRequest req, byte[] cuerpo) {
        return new ServerHttpRequestDecorator(req) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(cuerpo)));
            }
        };
    }
}
