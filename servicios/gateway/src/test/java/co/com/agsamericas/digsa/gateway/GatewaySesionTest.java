package co.com.agsamericas.digsa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S1.6: el gateway frente a una {@link SeguridadSimulada} y con Redis real (Testcontainers). Cubre, del lado del gateway, los bloqueantes
 * de PENDIENTES_I1: B1 salir (204, cierre en seguridad, sesión del gateway invalidada), B2 refresco fallido = 401 sin_sesion, B3 sid
 * revisado contra seguridad e inactividad, B4 /api/auth público con CSRF, límite por usuario y solo la cookie del emisor (ADR-014).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "LUME_GATEWAY_SECRETO=gateway-solo-prueba",
        "REDIS_CLAVE_GATEWAY=" + GatewayTest.CLAVE_REDIS,
        "lume.limite.usuario-por-minuto=3",
        "lume.limite.ip-por-minuto=1000"})
@Import(GatewaySesionTest.Reloj.class)
class GatewaySesionTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:8.10.3@sha256:f245f4c83b84a9e9a0588da1c9456e579455310f0945d5db52cd2ba27f2000cc")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--user", "default", "off",
                    "--user", "gateway", "on", ">" + GatewayTest.CLAVE_REDIS, "~lume:*", "&*", "+@all", "-@dangerous", "+info");

    static final SeguridadSimulada SEGURIDAD = new SeguridadSimulada();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("REDIS_HOST", REDIS::getHost);
        r.add("REDIS_PUERTO", () -> REDIS.getMappedPort(6379));
        r.add("LUME_SEGURIDAD_URL", SEGURIDAD::url);
        // Las APIs de servicio también van al doble (eco con la cabecera Authorization recibida)
        r.add("LUME_HOLA_URL", SEGURIDAD::url);
    }

    @AfterAll
    static void cerrar() {
        SEGURIDAD.close();
    }

    /** Reloj que la prueba adelanta (inactividad y ventanas del límite de tasa). */
    static class Movible extends Clock {
        volatile Instant ahora = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Reloj {
        @Bean
        @Primary
        Movible relojMovible() {
            return new Movible();
        }
    }

    @Value("${local.server.port}")
    int puerto;
    @Autowired
    Movible reloj;
    @Autowired
    ClienteSeguridad clienteSeguridad;

    WebTestClient web;
    Map<String, String> cookies;

    @BeforeEach
    void preparar() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).responseTimeout(Duration.ofSeconds(20)).build();
        cookies = new LinkedHashMap<>();
        reloj.ahora = Instant.now();
        clienteSeguridad.olvidar(SeguridadSimulada.SID); // el mismo sid en todas las pruebas: sin caché heredada
        SEGURIDAD.estado = SeguridadSimulada.vigente(10);
        SEGURIDAD.refrescoFalla = false;
        SEGURIDAD.accesoVenceEnSegundos = 300;
        SEGURIDAD.cierres.clear();
        SEGURIDAD.actividades.clear();
        SEGURIDAD.recibidas.clear();
    }

    // ---------- B4 / ADR-014: /api/auth/** ----------

    @Test
    @DisplayName("ADR-014: /api/auth/** es público pero exige el CSRF del gateway (419 sin él)")
    void elIngresoExigeCsrf() {
        web.post().uri("/api/auth/ingreso").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"usuario\":\"ana\",\"clave\":\"x\"}")
                .exchange().expectStatus().isEqualTo(419);
    }

    @Test
    @DisplayName("ADR-014: a seguridad solo viaja la cookie LUME_IDP, sin Authorization; de vuelta solo su Set-Cookie")
    void soloViajaLaCookieDelEmisor() {
        csrf();
        cookies.put("LUME_IDP", "del-navegador");
        cookies.put("LUME_SESION", "no-debe-salir");
        var r = post("/api/auth/ingreso", "{\"usuario\":\"ana\",\"clave\":\"x\"}").header(HttpHeaders.AUTHORIZATION, "Bearer falso")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        Map<String, String> recibida = SEGURIDAD.recibidas.getLast();
        assertThat(recibida.get("cookie")).isEqualTo("LUME_IDP=del-navegador");
        assertThat(recibida.get("authorization")).isEmpty();
        List<String> setCookie = r.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).anySatisfy(c -> assertThat(c).startsWith("LUME_IDP=del-emisor"));
        assertThat(setCookie).noneSatisfy(c -> assertThat(c).startsWith("INTRUSA="));
    }

    @Test
    @DisplayName("Bloqueante 4: límite por usuario en el ingreso (429 al pasarse) sin afectar a otro usuario")
    void limitePorUsuario() {
        csrf();
        for (int i = 0; i < 3; i++) {
            post("/api/auth/ingreso", "{\"usuario\":\"Bruno.Prueba \",\"clave\":\"x" + i + "\"}").exchange().expectStatus().isOk();
        }
        post("/api/auth/ingreso", "{\"usuario\":\"bruno.prueba\",\"clave\":\"y\"}").exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.mensaje").isEqualTo(LimiteIngresoGatewayFilterFactory.DEMASIADAS);
        post("/api/auth/ingreso", "{\"usuario\":\"otra.persona\",\"clave\":\"y\"}").exchange().expectStatus().isOk();
        // El cuerpo llega intacto a seguridad
        assertThat(SEGURIDAD.recibidas.getLast().get("ruta")).isEqualTo("/api/auth/ingreso");
        // La ventana siguiente vuelve a permitir
        reloj.ahora = reloj.ahora.plus(Duration.ofMinutes(1));
        post("/api/auth/ingreso", "{\"usuario\":\"bruno.prueba\",\"clave\":\"z\"}").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("M3: una ruta codificada (/api/auth/%69ngreso) no evade el límite: 400")
    void rutaCodificadaEs400() {
        csrf();
        web.post().uri(URI.create("http://localhost:" + puerto + "/api/auth/%69ngreso"))
                .header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN")).cookie("XSRF-TOKEN", cookies.get("XSRF-TOKEN"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"usuario\":\"ana\",\"clave\":\"x\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("M3: sin un usuario legible en el cuerpo, el mismo límite se aplica por IP")
    void sinUsuarioLegibleLimitaPorIp() {
        csrf();
        for (int i = 0; i < 3; i++) {
            post("/api/auth/ingreso", "{\"usuario\":[\"raro\"],\"clave\":\"x\"}").exchange().expectStatus().isOk();
        }
        post("/api/auth/ingreso", "{\"usuario\":[\"raro\"],\"clave\":\"x\"}").exchange().expectStatus().isEqualTo(429);
    }

    @Test
    @DisplayName("M1: el código tiene límite por sesión de ingreso (cookie del emisor)")
    void codigoLimitadoPorSesionDeIngreso() {
        csrf();
        cookies.put("LUME_IDP", "sesion-de-ingreso-ficticia");
        for (int i = 0; i < 3; i++) {
            post("/api/auth/codigo", "{\"codigo\":\"00000" + i + "\"}").exchange().expectStatus().isOk();
        }
        post("/api/auth/codigo", "{\"codigo\":\"999999\"}").exchange().expectStatus().isEqualTo(429);
        cookies.put("LUME_IDP", "otra-sesion-ficticia");
        post("/api/auth/codigo", "{\"codigo\":\"999999\"}").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("M4: del emisor, al público solo autorizar, JWKS y descubrimiento; token, revocación e introspección dan 404")
    void elEmisorPublicaSoloLoNecesario() {
        web.post().uri("/oauth2/token").exchange().expectStatus().isNotFound();
        web.post().uri("/oauth2/revoke").exchange().expectStatus().isNotFound();
        web.post().uri("/oauth2/introspect").exchange().expectStatus().isNotFound();
        web.get().uri("/userinfo").exchange().expectStatus().isNotFound();
        web.get().uri("/oauth2/jwks").exchange().expectStatus().isOk().expectBody().jsonPath("$.keys[0].kid").isEqualTo("prueba");
    }

    // ---------- B1: salir ----------

    @Test
    @DisplayName("Bloqueante 1: salir sin sesión responde 204")
    void salirSinSesion() {
        csrf();
        post("/bff/salir", null).exchange().expectStatus().isNoContent();
        assertThat(SEGURIDAD.cierres).isEmpty();
    }

    @Test
    @DisplayName("Bloqueante 1: salir con sesión cierra en seguridad (salida), invalida la sesión del gateway y responde 204")
    void salirConSesion() {
        ingresar();
        get("/api/sesion").exchange().expectStatus().isOk();
        csrf();
        post("/bff/salir", null).exchange().expectStatus().isNoContent();
        assertThat(SEGURIDAD.cierres).containsExactly(SeguridadSimulada.SID + " {\"motivo\":\"salida\"}");
        get("/api/sesion").exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.motivo").isEqualTo("sin_sesion");
    }

    // ---------- B2: refresco ----------

    @Test
    @DisplayName("Bloqueante 2: si el refresco falla (invalid_grant), 401 sin_sesion (nunca 500) y la sesión del gateway se cierra")
    void refrescoFallidoEs401() {
        SEGURIDAD.accesoVenceEnSegundos = 20; // dentro del margen de 60 s: el gateway refresca en la siguiente petición
        ingresar();
        SEGURIDAD.refrescoFalla = true;
        get("/api/sesion").exchange().expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.motivo").isEqualTo("sin_sesion");
        SEGURIDAD.refrescoFalla = false;
        get("/api/sesion").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Al crear la sesión (después del ingreso) el gateway rota XSRF-TOKEN: el token anterior ya no sirve")
    void elIngresoRotaElTokenCsrf() {
        csrf();
        String antes = cookies.get("XSRF-TOKEN");
        assertThat(antes).isNotBlank();
        ingresar();
        String despues = cookies.get("XSRF-TOKEN");
        assertThat(despues).isNotBlank().isNotEqualTo(antes);
        // El navegador ya tiene el nuevo (cookie): una escritura con la cabecera vieja y la cookie nueva es 419
        web.post().uri("/bff/salir").header("X-XSRF-TOKEN", antes).cookie("XSRF-TOKEN", despues)
                .cookie(ConfiguracionSeguridadGateway.SESION, cookies.get(ConfiguracionSeguridadGateway.SESION))
                .exchange().expectStatus().isEqualTo(419);
        // Y con el nuevo, pasa
        post("/bff/salir", null).exchange().expectStatus().isNoContent();
    }

    @Test
    @DisplayName("TokenRelay: con sesión, el servicio recibe el access token (Bearer) y no la cookie")
    void elServicioRecibeElToken() {
        ingresar();
        get("/api/sesion").exchange().expectStatus().isOk();
        Map<String, String> recibida = SEGURIDAD.recibidas.getLast();
        assertThat(recibida.get("authorization")).startsWith("Bearer ey");
        assertThat(recibida.get("cookie")).isEmpty();
    }

    // ---------- B3: sid e inactividad ----------

    @Test
    @DisplayName("Bloqueante 3: si seguridad dice que el sid ya no es vigente, 401 con su motivo y texto; la sesión del gateway se cierra")
    void sidNoVigente() {
        ingresar();
        String texto = "Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.";
        SEGURIDAD.estado = SeguridadSimulada.cerrada("reemplazada", texto);
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(31)); // vence la caché de 30 s
        int antes = SEGURIDAD.recibidas.size();
        get("/api/sesion").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.motivo").isEqualTo("reemplazada").jsonPath("$.mensaje").isEqualTo(texto);
        // La petición no llega al servicio: el gateway corta en el 401
        assertThat(SEGURIDAD.recibidas).hasSize(antes);
        SEGURIDAD.estado = SeguridadSimulada.vigente(10);
        get("/api/sesion").exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.motivo").isEqualTo("sin_sesion");
    }

    @Test
    @DisplayName("Inactividad: 9:59 sigue; a los 10:00 sin actividad, 401 inactividad y cierre en seguridad; estado no renueva")
    void inactividad() {
        ingresar();
        get("/api/sesion").exchange().expectStatus().isOk();
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(9 * 60 + 59));
        get("/api/sesion/estado").exchange().expectStatus().isOk(); // no cuenta como actividad
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(1));
        get("/api/sesion").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.motivo").isEqualTo("inactividad")
                .jsonPath("$.mensaje").isEqualTo(ConfiguracionSeguridadGateway.INACTIVIDAD);
        assertThat(SEGURIDAD.cierres).containsExactly(SeguridadSimulada.SID + " {\"motivo\":\"inactividad\"}");
    }

    @Test
    @DisplayName("Decisión 7.1: la actividad se informa a seguridad como máximo cada 30 s")
    void actividadCadaTreintaSegundos() {
        ingresar();
        get("/api/sesion").exchange().expectStatus().isOk();
        get("/api/sesion").exchange().expectStatus().isOk();
        esperar(() -> SEGURIDAD.actividades.size() == 1);
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(31));
        get("/api/sesion").exchange().expectStatus().isOk();
        esperar(() -> SEGURIDAD.actividades.size() == 2);
    }

    // ---------- auxiliares ----------

    /** Ingreso OIDC completo contra el doble: authorize (PKCE + nonce) → retorno con code → sesión LUME_SESION con los tokens. */
    private void ingresar() {
        EntityExchangeResult<Void> inicio = get("/bff/oauth2/authorization/lume").exchange().expectStatus().is3xxRedirection()
                .expectBody(Void.class).returnResult();
        guardar(inicio);
        var q = UriComponentsBuilder.fromUri(inicio.getResponseHeaders().getLocation()).build().getQueryParams();
        SEGURIDAD.nonce = java.net.URLDecoder.decode(q.getFirst("nonce"), java.nio.charset.StandardCharsets.UTF_8);
        String state = java.net.URLDecoder.decode(q.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8);
        EntityExchangeResult<Void> retorno = conCookies(web.get().uri(b -> b.path("/bff/login/oauth2/code/lume")
                        .queryParam("code", "codigo-ficticio").queryParam("state", "{s}").build(state)))
                .exchange().expectStatus().is3xxRedirection().expectBody(Void.class).returnResult();
        guardar(retorno);
        assertThat(retorno.getResponseHeaders().getLocation()).isEqualTo(URI.create("/"));
    }

    private void csrf() {
        guardar(get("/actuator/health").exchange().expectBody(Void.class).returnResult());
    }

    private WebTestClient.RequestHeadersSpec<?> get(String uri) {
        return conCookies(web.get().uri(uri));
    }

    private WebTestClient.RequestHeadersSpec<?> post(String uri, String json) {
        WebTestClient.RequestBodySpec b = web.post().uri(uri).header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""));
        conCookies(b);
        return json == null ? b : b.contentType(MediaType.APPLICATION_JSON).bodyValue(json);
    }

    private <S extends WebTestClient.RequestHeadersSpec<?>> S conCookies(S s) {
        cookies.forEach((k, v) -> s.cookie(k, v));
        return s;
    }

    private void guardar(EntityExchangeResult<?> r) {
        for (List<ResponseCookie> l : r.getResponseCookies().values()) {
            for (ResponseCookie c : l) {
                if (c.getMaxAge().isZero()) {
                    cookies.remove(c.getName());
                } else {
                    cookies.put(c.getName(), c.getValue());
                }
            }
        }
    }

    private static void esperar(java.util.function.BooleanSupplier condicion) {
        long hasta = System.currentTimeMillis() + 3000;
        while (!condicion.getAsBoolean() && System.currentTimeMillis() < hasta) {
            Thread.onSpinWait();
        }
        assertThat(condicion.getAsBoolean()).isTrue();
    }
}
