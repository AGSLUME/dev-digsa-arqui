package co.com.agsamericas.digsa.seguridad.emisor;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.seguridad.soporte.ContenedoresPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.CorreosCapturados;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import co.com.agsamericas.digsa.seguridad.soporte.RelojMovible;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * B6 contra el servidor real (Tomcat), directo al emisor y sin gateway: pasados 60 s del MFA, NINGUNA variante de la ruta de autorización
 * emite un código (revisión de la integradora sobre S1.7: «/oauth2/%61uthorize» lo emitía porque el filtro comparaba la ruta en crudo).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prueba")
@Import({ContenedoresPrueba.class, PruebaIntegracion.Dobles.class})
class RutasEmisorTest {

    private static final String CONSULTA = "?response_type=code&client_id=gateway&scope=openid&state=xyz"
            + "&redirect_uri=http://localhost:9000/bff/login/oauth2/code/lume"
            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256";

    @Value("${local.server.port}")
    int puerto;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CorreosCapturados correos;
    @Autowired
    RelojMovible reloj;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/oauth2/authorize", "/oauth2/%61uthorize", "/oauth2/authorize/", "/oauth2/AUTHORIZE", "/oauth2//authorize"})
    @DisplayName("B6: MFA correcto, nadie autoriza en 60 s → ninguna variante de la ruta emite código")
    void ningunaVarianteEmiteCodigoPasadosSesentaSegundos(String ruta) throws Exception {
        reloj.reiniciar();
        correos.limpiar();
        var u = new DatosPrueba(jdbc, reloj).usuario();
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://localhost:" + puerto;
        // 1) La autorización pendiente y el ingreso completo (contraseña + código), sin seguir «continuar»
        http.send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorize" + CONSULTA)).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(post(http, base + "/api/auth/ingreso", "{\"usuario\":\"" + u.usuario() + "\",\"clave\":\"" + DatosPrueba.CLAVE + "\"}"))
                .isEqualTo(200);
        assertThat(post(http, base + "/api/auth/codigo", "{\"codigo\":\"" + correos.ultimoCodigo(u.correo()) + "\"}")).isEqualTo(200);
        // 2) Pasan 61 s y alguien pide la autorización por la variante de la ruta
        reloj.avanzar(Duration.ofSeconds(61));
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + ruta + CONSULTA)).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.discarding());
        String ubicacion = r.headers().firstValue("Location").orElse("");
        assertThat(ubicacion).as("%s → %d %s", ruta, r.statusCode(), ubicacion).doesNotContain("code=");
        // Limpia la sesión de LUME abierta (B-b: se abre antes del canje) para no afectar a otras pruebas del mismo usuario
        jdbc.sql("UPDATE sesiones SET cerrada = now(), motivo = 'salida' WHERE usuario_id = :u AND cerrada IS NULL").param("u", u.id()).update();
    }

    private static int post(HttpClient http, String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
