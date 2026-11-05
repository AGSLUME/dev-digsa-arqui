package co.com.agsamericas.digsa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** El BFF con Redis real (Testcontainers). Seguridad no está arriba: se prueba lo que el gateway decide por sí mismo. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "LUME_GATEWAY_SECRETO=gateway-solo-prueba",
        "REDIS_CLAVE_GATEWAY=" + GatewayTest.CLAVE_REDIS})
class GatewayTest {

    /** Valor solo de prueba (ficticio). */
    static final String CLAVE_REDIS = "redis-solo-prueba";

    /** Misma imagen, digest y ACL que infra/docker-compose.yml (ADR-009, ADR-012): la sesión debe caber en "lume:*". */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:8.10.3@sha256:f245f4c83b84a9e9a0588da1c9456e579455310f0945d5db52cd2ba27f2000cc")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--user", "default", "off",
                    "--user", "gateway", "on", ">" + CLAVE_REDIS, "~lume:*", "&*", "+@all", "-@dangerous", "+info");

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("REDIS_HOST", REDIS::getHost);
        r.add("REDIS_PUERTO", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    int puerto;

    WebTestClient web;

    @BeforeEach
    void preparar() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void unaApiSinSesionResponde401EnJsonYNoRedirige() {
        web.get().uri("/api/hola").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.mensaje").isEqualTo(ConfiguracionSeguridadGateway.SIN_SESION)
                .jsonPath("$.motivo").isEqualTo("sin_sesion");
    }

    @Test
    void unaEscrituraSinTokenCsrfResponde419ComoElMonolito() {
        web.post().uri("/api/hola").exchange()
                .expectStatus().isEqualTo(419)
                .expectBody().jsonPath("$.mensaje").isEqualTo(ConfiguracionSeguridadGateway.PAGINA_EXPIRO);
    }

    @Test
    void elIngresoVaAlEmisorConPkce() {
        var r = web.get().uri("/bff/oauth2/authorization/lume").exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class);
        String destino = r.getResponseHeaders().getLocation().toString();
        assertThat(destino).startsWith("http://localhost:9000/oauth2/authorize?");
        assertThat(destino).contains("code_challenge=").contains("code_challenge_method=S256").contains("client_id=gateway");
        assertThat(r.getResponseHeaders().get("Set-Cookie")).anySatisfy(c -> assertThat(c)
                .startsWith(ConfiguracionSeguridadGateway.SESION + "=").containsIgnoringCase("HttpOnly").contains("SameSite=Strict"));
    }

    @Test
    void publicaLaCookieCsrfYLasCabecerasDeSeguridad() {
        var r = web.get().uri("/actuator/health").exchange().expectStatus().isOk().returnResult(String.class);
        assertThat(r.getResponseHeaders().get("Set-Cookie")).anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN="));
        assertThat(r.getResponseHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(r.getResponseHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    }
}
