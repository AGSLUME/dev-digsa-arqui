package co.com.agsamericas.digsa.gateway;

import java.time.Clock;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * La sesión de LUME en el gateway (ADR-004; PENDIENTES_I1, bloqueantes 2 y 3), en cada petición a {@code /api/**} con sesión (no en
 * {@code /api/auth/**}, que es público):
 * <ol>
 *   <li>Revisa el {@code sid} del id token contra {@code seguridad} (caché ≤30 s). Si no es vigente, cierra la sesión del gateway y
 *       responde 401 con el {@code motivo} y el texto del monolito (inactivo, clave, cerrada, reemplazada, clave_vencida, cambio_clave).</li>
 *   <li>Mide la inactividad con la hora exacta de cada petición y los minutos que da {@code seguridad}: al vencer, cierra la sesión en los
 *       dos lados y responde 401 {@code inactividad}. {@code /api/sesion/estado} no cuenta como actividad. La actividad se informa a
 *       {@code seguridad} como máximo cada 30 s (decisión 7.1).</li>
 *   <li>Si el refresco del token falla ({@code TokenRelay}: refresh token revocado, emisor reiniciado, ...), cierra la sesión del gateway y
 *       responde 401 {@code sin_sesion}, nunca 500.</li>
 * </ol>
 */
@Component
class FiltroSesionLume implements GlobalFilter, Ordered {

    static final String ULTIMO = "lume.sesion.ultimo";
    static final String INFORMADO = "lume.sesion.informado";
    static final String ESTADO = "/api/sesion/estado";
    /** Amarrado a {@code ServicioEstadoSesion.MARGEN_INFORME} de seguridad (≥ el doble): lo comprueba {@code MargenInactividadTest}. */
    private static final long INFORMAR_CADA_MS = 30_000;

    private final ClienteSeguridad seguridad;
    private final Clock reloj;

    FiltroSesionLume(ClienteSeguridad seguridad, Clock reloj) {
        this.seguridad = seguridad;
        this.reloj = reloj;
    }

    /** Antes de TokenRelay y del enrutamiento: así envuelve también el refresco del token. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ruta = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!ruta.startsWith("/api/") || ruta.startsWith("/api/auth/")) {
            return chain.filter(exchange);
        }
        // Optional y no switchIfEmpty: revisar() devuelve Mono<Void>, que siempre termina vacío, y switchIfEmpty seguiría la cadena hacia el
        // servicio también después de haber respondido el 401.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(a -> a instanceof OAuth2AuthenticationToken && a.isAuthenticated())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(a -> a.isEmpty()
                        ? chain.filter(exchange)
                        : exchange.getSession().flatMap(sesion -> revisar(exchange, chain, sesion, sid(a.get()), ruta)));
    }

    private Mono<Void> revisar(ServerWebExchange exchange, GatewayFilterChain chain, WebSession sesion, String sid, String ruta) {
        if (sid == null) {
            return cerrar(exchange, sesion, null, "sin_sesion", ConfiguracionSeguridadGateway.SIN_SESION);
        }
        return seguridad.estado(sid).flatMap(e -> {
            if (!e.vigente()) {
                return cerrar(exchange, sesion, sid, e.motivo(), e.mensaje());
            }
            long ahora = reloj.millis();
            Long ultimo = sesion.getAttribute(ULTIMO);
            if (ultimo != null && ahora - ultimo >= e.minutos() * 60_000L) {
                return seguridad.cerrar(sid, "inactividad")
                        .then(cerrar(exchange, sesion, sid, "inactividad", ConfiguracionSeguridadGateway.INACTIVIDAD));
            }
            if (!ESTADO.equals(ruta) || ultimo == null) {
                sesion.getAttributes().put(ULTIMO, ahora);
                Long informado = sesion.getAttribute(INFORMADO);
                if (!ESTADO.equals(ruta) && (informado == null || ahora - informado >= INFORMAR_CADA_MS)) {
                    sesion.getAttributes().put(INFORMADO, ahora);
                    seguridad.actividad(sid);
                }
            }
            return chain.filter(exchange)
                    .onErrorResume(OAuth2AuthorizationException.class,
                            err -> cerrar(exchange, sesion, sid, "sin_sesion", ConfiguracionSeguridadGateway.SIN_SESION));
        });
    }

    /** Cierra la sesión del gateway (tokens incluidos: viven en ella) y responde 401 con el motivo. */
    private Mono<Void> cerrar(ServerWebExchange exchange, WebSession sesion, String sid, String motivo, String mensaje) {
        if (sid != null) {
            seguridad.olvidar(sid);
        }
        return sesion.invalidate().then(Mono.defer(() -> ConfiguracionSeguridadGateway.responder(exchange.getResponse(), 401,
                mensaje == null ? ConfiguracionSeguridadGateway.SIN_SESION : mensaje, motivo == null ? "sin_sesion" : motivo)));
    }

    /** El {@code sid} del id token (lo pone el emisor en el ingreso, ADR-004). */
    static String sid(Authentication a) {
        return a.getPrincipal() instanceof OidcUser u ? u.getIdToken().getClaimAsString("sid") : null;
    }
}
