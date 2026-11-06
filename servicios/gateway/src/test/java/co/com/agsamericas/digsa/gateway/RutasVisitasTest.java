package co.com.agsamericas.digsa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** VA1.0: el gateway enruta las API de {@code visitas} (ruta {@code visitas}) con el token del usuario y sin cookies. Prorrogas es de B: aún no. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "LUME_GATEWAY_SECRETO=gateway-solo-prueba",
        "REDIS_CLAVE_GATEWAY=" + RutasVisitasTest.CLAVE_REDIS,
        "LUME_VISITAS_URL=http://localhost:9999"})
class RutasVisitasTest {

    /** Valor solo de prueba (ficticio). */
    static final String CLAVE_REDIS = "redis-solo-prueba";

    private static final List<String> RAICES = List.of("/api/catalogos", "/api/maestros", "/api/agenda", "/api/calendario",
            "/api/mis-visitas", "/api/solicitudes", "/api/propuesta");

    /** Misma imagen, digest y ACL que infra/docker-compose.yml (ADR-009, ADR-012). */
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

    @Autowired
    RouteDefinitionLocator rutas;

    @Test
    void laRutaVisitasApuntaAlServicioYCubreLasRaicesConYSinSubrutas() {
        RouteDefinition ruta = visitas();
        assertThat(ruta.getUri()).isEqualTo(URI.create("http://localhost:9999"));
        List<PathPattern> patrones = patrones(ruta);
        assertThat(patrones).isNotEmpty();
        // Como seguridad-api con /api/sesion: la raíz sin barra final va declarada, además del /**
        List<String> crudos = crudos(ruta);
        for (String raiz : RAICES) {
            assertThat(crudos).as("patrones de la ruta visitas").contains(raiz, raiz + "/**");
        }
        for (String raiz : RAICES) {
            assertThat(cubre(patrones, raiz)).as("raíz %s", raiz).isTrue();
            assertThat(cubre(patrones, raiz + "/")).as("%s/", raiz).isTrue();
            assertThat(cubre(patrones, raiz + "/123")).as("%s/123", raiz).isTrue();
            assertThat(cubre(patrones, raiz + "/a/b/c")).as("%s/a/b/c", raiz).isTrue();
        }
    }

    @Test
    void laRutaVisitasNoCubreLoQueNoEsSuyo() {
        List<PathPattern> patrones = patrones(visitas());
        // Prorrogas es del servicio de seguimiento (B): se agregará después. Seguridad, hola y el monolito siguen con lo suyo.
        for (String ajeno : new String[]{"/api/prorrogas", "/api/prorrogas/1", "/api/sesion", "/api/cuenta/clave", "/api/hola",
                "/api/analitica/x", "/api/presupuesto/x", "/api/agendas", "/api/catalogosx", "/"}) {
            assertThat(cubre(patrones, ajeno)).as("no debe cubrir %s", ajeno).isFalse();
        }
    }

    @Test
    void laRutaVisitasReenviaElTokenSinCookiesNiSetCookie() {
        List<FilterDefinition> filtros = visitas().getFilters();
        assertThat(filtros).extracting(FilterDefinition::getName).contains("TokenRelay", "RemoveRequestHeader", "RemoveResponseHeader");
        assertThat(filtros).filteredOn(f -> f.getName().equals("RemoveRequestHeader"))
                .anySatisfy(f -> assertThat(f.getArgs().values()).contains("Cookie"));
        assertThat(filtros).filteredOn(f -> f.getName().equals("RemoveResponseHeader"))
                .anySatisfy(f -> assertThat(f.getArgs().values()).contains("Set-Cookie"));
    }

    private RouteDefinition visitas() {
        List<RouteDefinition> todas = rutas.getRouteDefinitions().collectList().block();
        assertThat(todas).isNotNull();
        return todas.stream().filter(r -> "visitas".equals(r.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("No existe la ruta 'visitas' entre " + todas.stream().map(RouteDefinition::getId).toList()));
    }

    /** Todos los patrones de los predicados Path de la ruta (los argumentos de cada predicado, en cualquier forma de configuración). */
    private static List<PathPattern> patrones(RouteDefinition ruta) {
        PathPatternParser parser = new PathPatternParser();
        return crudos(ruta).stream().map(parser::parse).toList();
    }

    private static List<String> crudos(RouteDefinition ruta) {
        return ruta.getPredicates().stream()
                .filter(p -> p.getName().equals("Path"))
                .map(PredicateDefinition::getArgs)
                .flatMap(a -> a.values().stream())
                .flatMap(v -> java.util.Arrays.stream(v.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean cubre(List<PathPattern> patrones, String ruta) {
        var camino = org.springframework.http.server.PathContainer.parsePath(ruta);
        return patrones.stream().anyMatch(p -> p.matches(camino));
    }
}
