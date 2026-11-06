package co.com.agsamericas.digsa.visitas.maestros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.reglas.Bitacora.Operacion;
import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * VA1.4: obligaciones de la minuta (casos que el monolito no tenía). El bloqueo «ya se calificó en visitas» depende del puerto
 * {@link UsoObligaciones}, que aquí se sustituye por un mock (por defecto responde false, como el bean del servicio mientras no exista
 * la calificación, que es de visitas-b).
 */
class ObligacionesApiTest extends PruebaApi {

    private static final long COORD = 7_300_000_001L;
    private static final String TEXTO = "Texto de la obligación de prueba con longitud suficiente.";
    private static final String CALIFICADA = "La obligación ya se calificó en visitas: no cambie su código ni su prestador; inactívela y cree otra.";

    @MockitoBean
    UsoObligaciones uso;

    private static RequestPostProcessor coordinador() {
        return token(COORD, "MAESTROS");
    }

    private static String codigoNuevo() {
        return "OB-PRU-" + secuencia();
    }

    private static Map<String, Object> obligacion(String prestador, String codigo, String texto) {
        Map<String, Object> m = new HashMap<>();
        m.put("prestador", prestador);
        m.put("codigo", codigo);
        m.put("texto", texto);
        m.put("fuente", "Cláusula de prueba");
        return m;
    }

    private long crear(String prestador, String codigo) throws Exception {
        return idCreado(post("/api/maestros/obligaciones", coordinador(), obligacion(prestador, codigo, TEXTO)).andExpect(status().isCreated()));
    }

    private static void validacion(ResultActions r, String mensaje) throws Exception {
        r.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value(mensaje));
    }

    @Test
    @DisplayName("crear: 201 con id y «Obligación creada.»; código en mayúsculas, prestador en minúsculas, activa por defecto; evento sin el texto")
    void crear() throws Exception {
        String codigo = codigoNuevo();
        ResultActions r = post("/api/maestros/obligaciones", coordinador(), obligacion("OPL", codigo.toLowerCase(), TEXTO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value("Obligación creada."));
        long id = idCreado(r);
        Map<String, Object> fila = jdbc.sql("SELECT prestador, codigo, activo, fuente FROM obligaciones_contrato WHERE id = :i").param("i", id)
                .query().singleRow();
        assertThat(fila.get("prestador")).isEqualTo("opl");
        assertThat(fila.get("codigo")).isEqualTo(codigo);
        assertThat(fila.get("activo")).isEqualTo(true);
        get("/api/maestros/obligaciones", coordinador()).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")].codigo").value(hasItem(codigo)))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].prestador").value(hasItem("opl")))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].texto").value(hasItem(TEXTO)))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].activo").value(hasItem(true)));
        EventoAuditoria e = evento("catalogo_creado", Operacion.INSERCION, COORD, "Obligación " + codigo + " creada");
        assertThat(e.detalle()).doesNotContain(TEXTO);
    }

    @Test
    @DisplayName("la lista va ordenada por prestador, orden y código")
    void listaOrdenada() throws Exception {
        crear("fa", codigoNuevo());
        crear("opl", codigoNuevo());
        String json = cuerpo(get("/api/maestros/obligaciones", coordinador()).andExpect(status().isOk()));
        assertThat(json.lastIndexOf("\"prestador\":\"fa\"")).as("todas las de fa van antes que las de opl")
                .isLessThan(json.indexOf("\"prestador\":\"opl\""));
    }

    @Test
    @DisplayName("validaciones al crear: prestador, código OB-, texto de 10 a 2000 caracteres, duplicado; ninguna publica evento")
    void validacionesAlCrear() throws Exception {
        String codigo = codigoNuevo();
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("xx", codigo, TEXTO)),
                "El prestador debe ser el OPL o la farmacia alterna.");
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion(null, codigo, TEXTO)),
                "El prestador debe ser el OPL o la farmacia alterna.");
        String sinOb = "El código de la obligación empieza por OB- (p. ej. OB-OPL-01).";
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", "XX-1", TEXTO)), sinOb);
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", "OB-", TEXTO)), sinOb);
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", "OB-" + "A".repeat(21), TEXTO)), sinOb);
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", null, TEXTO)), sinOb);
        String textoMalo = "El texto de la obligación debe tener de 10 a 2000 caracteres.";
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", codigo, "corto")), textoMalo);
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", codigo, "x".repeat(2001))), textoMalo);
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", codigo, null)), textoMalo);
        assertThat(jdbc.sql("SELECT count(*) FROM obligaciones_contrato WHERE codigo = :c").param("c", codigo).query(Long.class).single()).isZero();

        // límites que sí se aceptan
        post("/api/maestros/obligaciones", coordinador(), obligacion("fa", codigo, "x".repeat(10))).andExpect(status().isCreated());
        post("/api/maestros/obligaciones", coordinador(), obligacion("fa", codigoNuevo(), "x".repeat(2000))).andExpect(status().isCreated());
        validacion(post("/api/maestros/obligaciones", coordinador(), obligacion("opl", codigo, TEXTO)), "Ya existe la obligación " + codigo + ".");
        assertThat(eventos("catalogo_creado")).hasSize(2);
    }

    @Test
    @DisplayName("editar: 200 «Obligación actualizada.»; activo nulo cuenta como true; 404 si no existe; mismas validaciones que al crear")
    void editar() throws Exception {
        String codigo = codigoNuevo();
        long id = crear("opl", codigo);
        Map<String, Object> e = obligacion("opl", codigo, "Texto editado de la obligación de prueba.");
        e.put("activo", false);
        put("/api/maestros/obligaciones/" + id, coordinador(), e).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Obligación actualizada."));
        assertThat(jdbc.sql("SELECT activo FROM obligaciones_contrato WHERE id = :i").param("i", id).query(Boolean.class).single()).isFalse();
        e.remove("activo");
        put("/api/maestros/obligaciones/" + id, coordinador(), e).andExpect(status().isOk());
        Map<String, Object> fila = jdbc.sql("SELECT texto, activo FROM obligaciones_contrato WHERE id = :i").param("i", id).query().singleRow();
        assertThat(fila.get("activo")).as("activo nulo cuenta como true").isEqualTo(true);
        assertThat(fila.get("texto")).isEqualTo("Texto editado de la obligación de prueba.");
        evento("catalogo_editado", Operacion.ACTUALIZACION, COORD, "Obligación " + codigo + " editada");

        put("/api/maestros/obligaciones/999999999", coordinador(), obligacion("opl", codigoNuevo(), TEXTO))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("La obligación no existe."));

        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("xx", codigo, TEXTO)),
                "El prestador debe ser el OPL o la farmacia alterna.");
        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("opl", "malo", TEXTO)),
                "El código de la obligación empieza por OB- (p. ej. OB-OPL-01).");
        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("opl", codigo, "corto")),
                "El texto de la obligación debe tener de 10 a 2000 caracteres.");
        String otro = codigoNuevo();
        crear("fa", otro);
        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("opl", otro, TEXTO)), "Ya existe la obligación " + otro + ".");
    }

    @Test
    @DisplayName("sin calificar (puerto en false) se puede cambiar el código y el prestador")
    void sinCalificarSePuedeCambiarLaClave() throws Exception {
        String codigo = codigoNuevo();
        long id = crear("opl", codigo);
        String nuevo = codigoNuevo();
        when(uso.calificada(id)).thenReturn(false);
        put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("fa", nuevo, TEXTO)).andExpect(status().isOk());
        Map<String, Object> fila = jdbc.sql("SELECT prestador, codigo FROM obligaciones_contrato WHERE id = :i").param("i", id).query().singleRow();
        assertThat(fila.get("prestador")).isEqualTo("fa");
        assertThat(fila.get("codigo")).isEqualTo(nuevo);
    }

    @Test
    @DisplayName("ya calificada (puerto en true): no cambia su código ni su prestador, pero sí su texto, su fuente y su estado")
    void calificadaConservaSuClave() throws Exception {
        String codigo = codigoNuevo();
        long id = crear("opl", codigo);
        when(uso.calificada(id)).thenReturn(true);
        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("opl", codigoNuevo(), TEXTO)), CALIFICADA);
        validacion(put("/api/maestros/obligaciones/" + id, coordinador(), obligacion("fa", codigo, TEXTO)), CALIFICADA);
        verify(uso, atLeastOnce()).calificada(id);
        Map<String, Object> intacta = jdbc.sql("SELECT prestador, codigo FROM obligaciones_contrato WHERE id = :i").param("i", id).query().singleRow();
        assertThat(intacta.get("prestador")).isEqualTo("opl");
        assertThat(intacta.get("codigo")).isEqualTo(codigo);
        assertThat(eventos("catalogo_editado")).isEmpty();

        Map<String, Object> igualClave = obligacion("opl", codigo.toLowerCase(), "Texto nuevo de una obligación ya calificada.");
        igualClave.put("activo", false);
        put("/api/maestros/obligaciones/" + id, coordinador(), igualClave).andExpect(status().isOk());
        Map<String, Object> fila = jdbc.sql("SELECT texto, activo FROM obligaciones_contrato WHERE id = :i").param("i", id).query().singleRow();
        assertThat(fila.get("texto")).isEqualTo("Texto nuevo de una obligación ya calificada.");
        assertThat(fila.get("activo")).isEqualTo(false);
    }

    @Test
    @DisplayName("sin la pantalla MAESTROS: 403; sin token: 401")
    void autorizacion() throws Exception {
        RequestPostProcessor sinPantalla = token(7_300_000_002L, "CATALOGOS");
        post("/api/maestros/obligaciones", sinPantalla, obligacion("opl", codigoNuevo(), TEXTO)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("No tiene permiso para esta acción."));
        put("/api/maestros/obligaciones/1", sinPantalla, obligacion("opl", codigoNuevo(), TEXTO)).andExpect(status().isForbidden());
        get("/api/maestros/obligaciones", sinPantalla).andExpect(status().isForbidden());
        get("/api/maestros/obligaciones", null).andExpect(status().isUnauthorized());
        post("/api/maestros/obligaciones", null, obligacion("opl", codigoNuevo(), TEXTO)).andExpect(status().isUnauthorized());
        assertThat(auditoria.eventos()).isEmpty();
    }
}
