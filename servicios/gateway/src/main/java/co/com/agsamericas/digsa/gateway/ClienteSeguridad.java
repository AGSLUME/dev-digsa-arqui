package co.com.agsamericas.digsa.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Cliente de {@code /interno/sesiones} de {@code seguridad} (contrato seguridad.yaml), con el token de máquina del propio gateway
 * ({@code client_credentials}, alcance {@code seguridad:sesiones}; registro {@code lume-interno}). El estado de cada sesión se guarda en
 * memoria {@link #CACHE} como máximo (ADR-004: «caché de 30 s como máximo»); un cierre lo borra de inmediato.
 *
 * <p>Falla cerrado: si {@code seguridad} no responde, la sesión no se da por vigente (la petición recibe 401 y el usuario vuelve a
 * ingresar cuando el servicio vuelva). El circuit breaker por ruta llega con Resilience4j (ADR-009).
 */
@Component
class ClienteSeguridad {

    static final Duration CACHE = Duration.ofSeconds(30);
    private static final Duration ESPERA = Duration.ofSeconds(3);
    private static final Logger LOG = LoggerFactory.getLogger(ClienteSeguridad.class);

    /** Respuesta de {@code GET /interno/sesiones/{sid}}. */
    record Estado(boolean vigente, int minutos, Long uid, String motivo, String mensaje) {
        static Estado noDisponible() {
            return new Estado(false, 0, null, "sin_sesion", ConfiguracionSeguridadGateway.SIN_SESION);
        }
    }

    private record EnCache(Estado estado, long hastaMs) {
    }

    private final WebClient http;
    private final Clock reloj;
    private final Map<String, EnCache> cache = new ConcurrentHashMap<>();

    ClienteSeguridad(ReactiveClientRegistrationRepository registros, ReactiveOAuth2AuthorizedClientService guardados,
                     Clock reloj, @Value("${LUME_SEGURIDAD_URL:http://localhost:9101}") String seguridad) {
        // Gestor propio (no un bean): un ReactiveOAuth2AuthorizedClientManager publicado lo tomaría TokenRelay para los tokens de usuario
        var clientes = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registros, guardados);
        clientes.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth = new ServerOAuth2AuthorizedClientExchangeFilterFunction(clientes);
        oauth.setDefaultClientRegistrationId("lume-interno");
        // WebClient propio (Boot 4 no publica un WebClient.Builder sin el starter de cliente web; no se agrega una dependencia por esto)
        this.http = WebClient.builder().baseUrl(seguridad).filter(oauth).build();
        this.reloj = reloj;
    }

    /** Estado de la sesión, de la caché si tiene menos de {@link #CACHE}. */
    Mono<Estado> estado(String sid) {
        long ahora = reloj.millis();
        EnCache c = cache.get(sid);
        if (c != null && ahora < c.hastaMs()) {
            return Mono.just(c.estado());
        }
        return http.get().uri("/interno/sesiones/{sid}", sid).accept(MediaType.APPLICATION_JSON)
                .retrieve().bodyToMono(Estado.class)
                .timeout(ESPERA)
                .doOnNext(e -> {
                    if (e.vigente()) {
                        cache.put(sid, new EnCache(e, ahora + CACHE.toMillis()));
                    } else {
                        cache.remove(sid);
                    }
                })
                .onErrorResume(e -> {
                    LOG.warn("[sesion] No se pudo consultar la sesión en seguridad: {}", e.getClass().getSimpleName());
                    return Mono.just(Estado.noDisponible());
                });
    }

    /** Actividad de la sesión (decisión 7.1). Sin esperar ni fallar la petición del usuario. */
    void actividad(String sid) {
        http.post().uri("/interno/sesiones/{sid}/actividad", sid).retrieve().toBodilessEntity().timeout(ESPERA)
                .subscribe(r -> { }, e -> LOG.warn("[sesion] No se pudo informar la actividad: {}", e.getClass().getSimpleName()));
    }

    /** Cierra la sesión en seguridad (revoca el refresh token y la sesión del emisor). {@code salida} o {@code inactividad}. */
    Mono<Void> cerrar(String sid, String motivo) {
        cache.remove(sid);
        return http.post().uri("/interno/sesiones/{sid}/cerrar", sid).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("motivo", motivo)).retrieve().toBodilessEntity().timeout(ESPERA).then()
                .onErrorResume(e -> {
                    LOG.error("[sesion] No se pudo cerrar la sesión en seguridad ({}): {}", motivo, e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** Olvida el estado guardado (al cerrar la sesión del gateway). */
    void olvidar(String sid) {
        cache.remove(sid);
    }
}
