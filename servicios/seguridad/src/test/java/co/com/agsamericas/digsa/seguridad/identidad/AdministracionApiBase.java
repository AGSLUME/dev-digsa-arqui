package co.com.agsamericas.digsa.seguridad.identidad;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ayudas de las pruebas de la administración de usuarios ({@code /api/usuarios/**}, S2). Cada prueba parte de un Administrador nuevo con
 * sesión abierta; cada petición lleva el token como lo reenvía el gateway. Las API revisan perfiles, pantallas y sesión en la base.
 */
abstract class AdministracionApiBase extends PruebaIntegracion {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final String SIN_PERMISO = "No tiene permiso para esta acción.";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    protected DatosPrueba.Ficticio admin;
    protected UUID sidAdmin;
    protected RequestPostProcessor comoAdmin;

    @BeforeEach
    void abrirAdministrador() {
        admin = administrador();
        sidAdmin = datos.abrirSesion(admin.id());
        comoAdmin = token(admin, sidAdmin);
    }

    /** El token de un usuario al que se le abre una sesión nueva (cierra la anterior, si había). */
    protected RequestPostProcessor sesionDe(DatosPrueba.Ficticio u) {
        return token(u, datos.abrirSesion(u.id()));
    }

    // ---------- Peticiones (por defecto, como el Administrador de la prueba) ----------

    protected ResultActions leer(String ruta) throws Exception {
        return leer(comoAdmin, ruta);
    }

    protected ResultActions leer(RequestPostProcessor quien, String ruta) throws Exception {
        return mvc.perform(get(ruta).with(quien));
    }

    protected ResultActions publicar(String ruta, Object cuerpo) throws Exception {
        return publicar(comoAdmin, ruta, cuerpo);
    }

    protected ResultActions publicar(RequestPostProcessor quien, String ruta, Object cuerpo) throws Exception {
        return mvc.perform(conCuerpo(post(ruta), cuerpo).with(quien));
    }

    protected ResultActions poner(String ruta, Object cuerpo) throws Exception {
        return poner(comoAdmin, ruta, cuerpo);
    }

    protected ResultActions poner(RequestPostProcessor quien, String ruta, Object cuerpo) throws Exception {
        return mvc.perform(conCuerpo(put(ruta), cuerpo).with(quien));
    }

    private static MockHttpServletRequestBuilder conCuerpo(MockHttpServletRequestBuilder b, Object cuerpo) {
        b.contentType(MediaType.APPLICATION_JSON);
        if (cuerpo != null) {
            b.content(cuerpo instanceof String s ? s : JSON.writeValueAsString(cuerpo));
        }
        return b;
    }

    // ---------- Datos ----------

    /** Un nombre de usuario válido y único en toda la ejecución (minúsculas; ^[a-z0-9._-]{3,50}$). */
    protected static String unico(String prefijo) {
        return (prefijo + SECUENCIA.incrementAndGet() + "." + System.nanoTime() % 1_000_000).toLowerCase();
    }

    protected static String correoDe(String usuario) {
        return usuario + "@" + DatosPrueba.DOMINIO;
    }

    /** Cuerpo de {@code DatosUsuario} con datos válidos; el documento va siempre (obligatorio para el Auditor). */
    protected static Map<String, Object> datosUsuario(String usuario, Integer... perfiles) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", "Persona Ficticia " + usuario);
        m.put("usuario", usuario);
        m.put("correo", correoDe(usuario));
        m.put("documento", "12345678");
        m.put("perfiles", List.of(perfiles));
        return m;
    }

    protected static JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    protected static String mensaje(ResultActions r) throws Exception {
        return json(r).path("mensaje").asString();
    }

    /** Crea por la API como el Administrador de la prueba y devuelve el id (exige 201). */
    protected long crearUsuarioPorApi(Map<String, Object> cuerpo) throws Exception {
        ResultActions r = publicar("/api/usuarios", cuerpo);
        r.andExpect(status().isCreated());
        return json(r).path("id").asLong();
    }

    protected long contar(String sql, Object... params) {
        var consulta = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            consulta = consulta.param(params[i]);
        }
        return consulta.query(Long.class).single();
    }
}
