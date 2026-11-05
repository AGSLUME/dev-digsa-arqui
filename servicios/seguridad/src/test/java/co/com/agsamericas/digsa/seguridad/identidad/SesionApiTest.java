package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * S1.5: {@code /api/sesion*} (ControladorSesion del monolito: IngresoYSesionApiTest y CuentaYSeguridadApiTest) y {@code /interno/sesiones}
 * para el gateway: las reglas de FiltroSesion (inactivo, clave cambiada, cerrada, vencida, cambio exigido) y el cierre con revocación.
 */
class SesionApiTest extends PruebaIntegracion {

    @Autowired
    ServicioSesiones cierres;

    private UUID abrir(DatosPrueba.Ficticio u) {
        return cierres.abrir(u.id(), 10, "203.0.113.10").orElseThrow();
    }

    private static RequestPostProcessor token(DatosPrueba.Ficticio u, UUID sid) {
        return jwt().jwt(j -> j.claim("uid", u.id()).claim("usuario", u.usuario()).claim("sid", sid.toString()));
    }

    private static RequestPostProcessor gateway() {
        return jwt().jwt(j -> j.subject("gateway").claim("scope", "seguridad:sesiones"))
                .authorities(new SimpleGrantedAuthority("SCOPE_seguridad:sesiones"));
    }

    private Instant ultimo(UUID sid) {
        return jdbc.sql("SELECT ultimo FROM sesiones WHERE id = :id").param("id", sid).query(java.sql.Timestamp.class).single().toInstant();
    }

    // ---------- /api/sesion ----------

    @Test
    @DisplayName("ControladorSesion: quién es, perfiles visibles, menú y pantallas de su perfil, minutos de inactividad")
    void datosDeLaSesion() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        UUID sid = abrir(u);
        mvc.perform(get("/api/sesion").with(token(u, sid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.id()))
                .andExpect(jsonPath("$.usuario").value(u.usuario()))
                .andExpect(jsonPath("$.perfiles[0]").value("Coordinador"))
                .andExpect(jsonPath("$.menu[?(@.ruta == 'agenda')]").exists())
                .andExpect(jsonPath("$.menu[?(@.ruta == 'usuarios')]").doesNotExist())
                .andExpect(jsonPath("$.pantallas[?(@ == 'visita')]").exists())
                .andExpect(jsonPath("$.menu[?(@.ruta == 'visita')]").doesNotExist())
                .andExpect(jsonPath("$.minutosInactividad").value(10));
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest: /api/sesion/estado no renueva la sesión; /mantener sí (solo GET)")
    void estadoNoRenuevaYMantenerSi() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        Instant antes = ultimo(sid);
        reloj.avanzar(Duration.ofMinutes(4));
        mvc.perform(get("/api/sesion/estado").with(token(u, sid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutos").value(10))
                .andExpect(jsonPath("$.restantes").value(6 * 60));
        assertThat(ultimo(sid)).isEqualTo(antes);
        mvc.perform(get("/api/sesion/mantener").with(token(u, sid))).andExpect(status().isOk()).andExpect(jsonPath("$.minutos").value(10));
        assertThat(ultimo(sid)).isAfter(antes);
        mvc.perform(post("/api/sesion/mantener").with(token(u, sid))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("FiltroSesion: con la sesión cerrada por el administrador, /api/sesion responde 401 cerrada")
    void sesionCerradaResponde401() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        cierres.cerrar(sid, RepositorioSesiones.Motivo.CERRADA);
        mvc.perform(get("/api/sesion").with(token(u, sid)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.motivo").value("cerrada"))
                .andExpect(jsonPath("$.mensaje").value("Su sesión fue cerrada. Vuelva a iniciar sesión."));
    }

    // ---------- /interno/sesiones ----------

    @Test
    @DisplayName("/interno exige un token de máquina con seguridad:sesiones; un token de usuario no entra")
    void elInternoExigeElAlcance() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        mvc.perform(get("/interno/sesiones/" + sid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/interno/sesiones/" + sid).with(token(u, sid))).andExpect(status().isForbidden());
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vigente").value(true))
                .andExpect(jsonPath("$.uid").value(u.id()))
                .andExpect(jsonPath("$.minutos").value(10));
    }

    @Test
    @DisplayName("FiltroSesion 2: usuario inactivado → inactivo, y la sesión queda cerrada")
    void usuarioInactivado() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        jdbc.sql("UPDATE usuarios SET activo = false WHERE id = :id").param("id", u.id()).update();
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.vigente").value(false))
                .andExpect(jsonPath("$.motivo").value("inactivo"))
                .andExpect(jsonPath("$.mensaje").value("Su usuario está inactivo."));
        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE id = :id").param("id", sid).query(String.class).single()).isEqualTo("inactivo");
    }

    @Test
    @DisplayName("FiltroSesion 3: contraseña cambiada en otro lado después del inicio → clave")
    void claveCambiadaDespues() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        reloj.avanzar(Duration.ofSeconds(5));
        jdbc.sql("UPDATE usuarios SET clave_actualizada = :t WHERE id = :id").param("t", java.sql.Timestamp.from(reloj.instant()))
                .param("id", u.id()).update();
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.motivo").value("clave"))
                .andExpect(jsonPath("$.mensaje").value("La contraseña cambió. Inicie sesión de nuevo."));
    }

    @Test
    @DisplayName("FiltroSesion 6: contraseña vencida con la sesión abierta → clave_vencida")
    void claveVencida() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        jdbc.sql("UPDATE usuarios SET clave_vence = now() - interval '1 day' WHERE id = :id").param("id", u.id()).update();
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.motivo").value("clave_vencida"))
                .andExpect(jsonPath("$.mensaje").value("Su contraseña venció. Ingrese de nuevo para cambiarla."));
    }

    @Test
    @DisplayName("FiltroSesion 7: cambio exigido por el administrador → cambio_clave con el enlace al correo (una sola vez)")
    void cambioExigido() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        jdbc.sql("UPDATE usuarios SET debe_cambiar_clave = true WHERE id = :id").param("id", u.id()).update();
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.motivo").value("cambio_clave"))
                .andExpect(jsonPath("$.mensaje").value("Debe actualizar su contraseña. Le enviamos un enlace a su correo."));
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway())).andExpect(jsonPath("$.motivo").value("cambio_clave"));
        assertThat(correos.a(u.correo())).hasSize(1);
    }

    @Test
    @DisplayName("Bloqueante 1: cerrar por salida revoca las autorizaciones y la sesión del emisor; queda sin_sesion; idempotente")
    void cerrarPorSalidaRevoca() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        jdbc.sql("""
                INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type)
                VALUES (:id, 'gateway', :p, 'authorization_code')""").param("id", UUID.randomUUID().toString()).param("p", sid.toString())
                .update();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/interno/sesiones/" + sid + "/cerrar").with(gateway()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"motivo\":\"salida\"}")).andExpect(status().isNoContent());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name = :p").param("p", sid.toString())
                .query(Long.class).single()).isZero();
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.vigente").value(false)).andExpect(jsonPath("$.motivo").value("sin_sesion"));
        // Ya sin sesión, un ingreso nuevo del mismo usuario no choca con la anterior
        assertThat(cierres.abrir(u.id(), 10, "203.0.113.10")).isPresent();
    }

    @Test
    @DisplayName("Inactividad medida por el gateway: cerrar por inactividad deja el motivo inactividad")
    void cerrarPorInactividad() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        mvc.perform(post("/interno/sesiones/" + sid + "/cerrar").with(gateway()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"inactividad\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/interno/sesiones/" + sid).with(gateway()))
                .andExpect(jsonPath("$.motivo").value("inactividad"))
                .andExpect(jsonPath("$.mensaje").value("La sesión se cerró por inactividad. Vuelva a iniciar sesión."));
        mvc.perform(post("/interno/sesiones/" + sid + "/cerrar").with(gateway()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"otra\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Decisión 7.1: la actividad informada por el gateway mantiene bloqueado un segundo ingreso")
    void laActividadInformadaBloqueaOtroIngreso() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        reloj.avanzar(Duration.ofMinutes(9));
        mvc.perform(post("/interno/sesiones/" + sid + "/actividad").with(gateway())).andExpect(status().isNoContent());
        reloj.avanzar(Duration.ofMinutes(9));
        assertThat(cierres.abrir(u.id(), 10, "203.0.113.11")).as("hubo actividad hace 9 min").isEmpty();
        reloj.avanzar(Duration.ofMinutes(2));
        assertThat(cierres.abrir(u.id(), 10, "203.0.113.11")).as("ya pasaron 11 min sin actividad").isPresent();
    }

    @Test
    @DisplayName("Un token real de máquina del cliente gateway (client_credentials) entra a /interno; su aud es seguridad")
    void tokenRealDelGateway() throws Exception {
        var u = datos.usuario();
        UUID sid = abrir(u);
        String basico = "Basic " + Base64.getEncoder().encodeToString("gateway:gateway-solo-prueba".getBytes(StandardCharsets.UTF_8));
        String cuerpo = mvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, basico)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("grant_type=client_credentials&scope=seguridad:sesiones"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String acceso = JsonMapper.builder().build().readTree(cuerpo).get("access_token").asString();
        mvc.perform(get("/interno/sesiones/" + sid).header(HttpHeaders.AUTHORIZATION, "Bearer " + acceso))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vigente").value(true));
    }
}
