package co.com.agsamericas.digsa.visitas.maestros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.reglas.Bitacora.Operacion;
import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * VA1.4: paridad de {@code /api/maestros/**} (puntos, auditores y operador logístico) con el monolito (MaestrosApiTest de digsa-java).
 * Autoriza la pantalla MAESTROS del token. Los usuarios (auditores, alcance de punto) se siembran en {@code usuarios_copia}. Las
 * obligaciones están en {@link ObligacionesApiTest}.
 */
class MaestrosApiTest extends PruebaApi {

    private static final long COORD = 7_200_000_001L;
    private static final String SIN_PERMISO = "No tiene permiso para esta acción.";

    private static RequestPostProcessor coordinador() {
        return token(COORD, "MAESTROS");
    }

    private static Map<String, Object> punto(String nombre, String unidad) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", nombre);
        m.put("unidad", unidad);
        m.put("fuerza", "EJC");
        m.put("modalidad", "Dispensación");
        m.put("municipio", "05001");
        m.put("direccion", "Calle 1 # 2-3");
        m.put("contacto", "Responsable del punto");
        m.put("correos", List.of("Punto.Uno@Example.com; punto.uno@example.com", "otro.punto@example.com"));
        return m;
    }

    private long crearPunto(Map<String, Object> cuerpo) throws Exception {
        return idCreado(post("/api/maestros/puntos", coordinador(), cuerpo).andExpect(status().isCreated()));
    }

    private static void revise(ResultActions r, String... errores) throws Exception {
        r.andExpect(status().isUnprocessableEntity());
        String m = cuerpo(r);
        assertThat(m).contains("Revise el punto: ");
        for (String e : errores) {
            assertThat(m).contains(e);
        }
    }

    // ---------------------------------------------------------------- puntos: lectura

    @Test
    @DisplayName("los 185 puntos del anexo del OPL se listan con filtros (unidad, fuerza, texto sin distinguir mayúsculas, activo)")
    void listaYFiltros() throws Exception {
        ResultActions fa = get("/api/maestros/puntos?unidad=FA", coordinador()).andExpect(status().isOk());
        String json = cuerpo(fa);
        assertThat(json).contains("\"unidad\":\"FA\"").doesNotContain("\"unidad\":\"ESM\"");
        fa.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(10)));
        assertThat(cuerpo(get("/api/maestros/puntos?texto=hospital naval", coordinador()))).contains("AMBULATORIA", "HOSPITALARIA");
        assertThat(cuerpo(get("/api/maestros/puntos?texto=HOSPITAL NAVAL", coordinador()))).contains("AMBULATORIA", "HOSPITALARIA");
        get("/api/maestros/puntos?fuerza=FAC&unidad=ESM", coordinador())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(14)));
        get("/api/maestros/puntos?activo=false&unidad=FA", coordinador()).andExpect(status().isOk());
        assertThat(cuerpo(get("/api/maestros/puntos?activo=true", coordinador()))).doesNotContain("\"activo\":false");
        // el texto del usuario no es un patrón: % y _ no son comodines
        get("/api/maestros/puntos?texto=%", coordinador()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // ---------------------------------------------------------------- puntos: escritura

    @Test
    @DisplayName("crear y editar un punto: correos sin repetir, NIT solo en FA, antes/después sin los datos de contacto")
    void crearYEditar() throws Exception {
        String nombre = unico("ESM de prueba ");
        Map<String, Object> p = punto(nombre, "ESM");
        p.put("nit", "900123456");
        long id = idCreado(post("/api/maestros/puntos", coordinador(), p)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value("Punto creado.")));
        Map<String, Object> guardado = jdbc.sql("SELECT nit, correos::text AS correos FROM puntos WHERE id = :id").param("id", id).query().singleRow();
        assertThat(guardado.get("nit")).as("el NIT se ignora en un ESM").isNull();
        assertThat((String) guardado.get("correos")).isEqualTo("{punto.uno@example.com,otro.punto@example.com}");
        get("/api/maestros/puntos?texto=" + nombre, coordinador()).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].exigeQf").value(false)).andExpect(jsonPath("$[0].activo").value(true));

        post("/api/maestros/puntos", coordinador(), punto(nombre, "ESM")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un punto con ese nombre en esa unidad, o con esa sigla."));

        Map<String, Object> e = punto(nombre, "ESM");
        e.put("modalidad", "Suministro");
        e.put("contacto", "Otra persona");
        e.put("exigeQf", true);
        put("/api/maestros/puntos/" + id, coordinador(), e).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Punto actualizado."));

        EventoAuditoria creado = evento("punto_creado", Operacion.INSERCION, COORD, "Punto " + id + " creado: «" + nombre + "» (ESM, EJC)");
        assertThat(creado.detalle()).doesNotContain("example.com");
        EventoAuditoria cambio = evento("punto_editado", Operacion.ACTUALIZACION, COORD, "Punto " + id + ":");
        assertThat(cambio.detalle()).contains("modalidad: «Dispensación» → «Suministro»", "contacto: modificado", "exigeQf: «false» → «true»")
                .doesNotContain("Otra persona").doesNotContain("Responsable del punto").doesNotContain("example.com");
    }

    @Test
    @DisplayName("el nombre es único por unidad (el mismo nombre en otra unidad sí) y la sigla es única; 404 al editar un punto inexistente")
    void unicidadYNoEncontrado() throws Exception {
        String nombre = unico("Punto repetido ");
        Map<String, Object> fa = punto(nombre, "FA");
        crearPunto(fa);
        post("/api/maestros/puntos", coordinador(), fa).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un punto con ese nombre en esa unidad, o con esa sigla."));
        crearPunto(punto(nombre, "NC")); // otra unidad
        String sigla = "TS" + secuencia();
        Map<String, Object> uno = punto(unico("Punto sigla uno "), "FA");
        uno.put("sigla", sigla);
        crearPunto(uno);
        Map<String, Object> dos = punto(unico("Punto sigla dos "), "FA");
        dos.put("sigla", sigla);
        post("/api/maestros/puntos", coordinador(), dos).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un punto con ese nombre en esa unidad, o con esa sigla."));

        put("/api/maestros/puntos/999999999", coordinador(), punto(unico("No existe "), "FA"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El punto no existe."));
        put("/api/maestros/puntos/999999999/estado", coordinador(), Map.of("activo", false))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El punto no existe."));
    }

    @Test
    @DisplayName("la modalidad solo se guarda en ESM y el NIT solo en FA")
    void modalidadSoloEnEsmYNitSoloEnFa() throws Exception {
        Map<String, Object> fa = punto(unico("FA con NIT "), "FA");
        fa.put("nit", "900123456-7");
        long id = crearPunto(fa);
        Map<String, Object> fila = jdbc.sql("SELECT nit, modalidad FROM puntos WHERE id = :i").param("i", id).query().singleRow();
        assertThat(fila.get("nit")).isEqualTo("900123456-7");
        assertThat(fila.get("modalidad")).as("la modalidad no se guarda fuera del ESM").isNull();
    }

    @Test
    @DisplayName("validaciones del punto: errores acumulados en «Revise el punto: …», nivel central sin fuerza = NA")
    void validaciones() throws Exception {
        Map<String, Object> sinModalidad = punto(unico("Sin modalidad "), "ESM");
        sinModalidad.remove("modalidad");
        revise(post("/api/maestros/puntos", coordinador(), sinModalidad), "falta la modalidad del ESM (Dispensación, Suministro, Consignación o Satélite)");

        Map<String, Object> mala = punto(unico("Unidad mala "), "XYZ");
        mala.put("municipio", "99999");
        revise(post("/api/maestros/puntos", coordinador(), mala), "la unidad debe ser ESM, FA o NC", "el municipio no existe");

        Map<String, Object> varios = punto("ab", "ESM");
        varios.put("fuerza", "NOEXISTE");
        varios.put("llegaDesde", "99999");
        varios.put("regionalId", 999999999L);
        varios.put("auditorAsignado", 999999999L);
        varios.put("sigla", "S".repeat(31));
        varios.put("nit", "9".repeat(21));
        varios.put("direccion", "D".repeat(201));
        varios.put("contacto", "C".repeat(151));
        varios.put("nivel", "N".repeat(41));
        varios.put("horario", "H".repeat(201));
        varios.put("correos", List.of("no-es-correo"));
        revise(post("/api/maestros/puntos", coordinador(), varios), "el nombre debe tener de 3 a 200 caracteres",
                "la fuerza no existe o está inactiva", "el municipio «se llega desde» no existe", "la regional no existe o está inactiva",
                "el auditor asignado no es un auditor activo", "la sigla tiene máximo 30 caracteres", "la dirección tiene máximo 200 caracteres",
                "el contacto tiene máximo 150 caracteres", "el nivel tiene máximo 40 caracteres", "el horario tiene máximo 200 caracteres",
                "el correo «no-es-correo» no es válido");

        List<String> once = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            once.add("c" + i + "@example.com");
        }
        Map<String, Object> muchos = punto(unico("Muchos correos "), "ESM");
        muchos.put("correos", once);
        revise(post("/api/maestros/puntos", coordinador(), muchos), "máximo 10 correos");
        assertThat(auditoria.eventos()).extracting(EventoAuditoria::tipo).doesNotContain("punto_creado");

        Map<String, Object> nc = punto(unico("Nivel central "), "NC");
        nc.put("fuerza", "");
        long id = crearPunto(nc);
        assertThat(jdbc.sql("SELECT fuerza FROM puntos WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("NA");
    }

    @Test
    @DisplayName("el auditor asignado debe ser un auditor activo de la copia de usuarios; la regional, una regional activa")
    void auditorYRegionalDelPunto() throws Exception {
        long activo = sembrarAuditor("Auditor activo del punto", true);
        long inactivo = sembrarAuditor("Auditor inactivo del punto", false);
        long coordinadorCopia = sembrarUsuario("Coordinador no auditor", true, 2);
        for (long malo : new long[]{inactivo, coordinadorCopia}) {
            Map<String, Object> p = punto(unico("Punto auditor malo "), "ESM");
            p.put("auditorAsignado", malo);
            revise(post("/api/maestros/puntos", coordinador(), p), "el auditor asignado no es un auditor activo");
        }
        long regional = jdbc.sql("SELECT id FROM par_regionales WHERE nombre = 'Bogotá'").query(Long.class).single();
        Map<String, Object> bueno = punto(unico("Punto auditor bueno "), "ESM");
        bueno.put("auditorAsignado", activo);
        bueno.put("regionalId", regional);
        long id = crearPunto(bueno);
        Map<String, Object> fila = jdbc.sql("SELECT auditor_asignado, regional_id FROM puntos WHERE id = :i").param("i", id).query().singleRow();
        assertThat(fila.get("auditor_asignado")).isEqualTo(activo);
        assertThat(fila.get("regional_id")).isEqualTo(regional);
    }

    @Test
    @DisplayName("activar e inactivar un punto; con usuarios ESM activos (usuarios_copia.alcance_punto) no se inactiva")
    void estadoDelPunto() throws Exception {
        long id = crearPunto(punto(unico("ESM de estado "), "ESM"));
        put("/api/maestros/puntos/" + id + "/estado", coordinador(), Map.of("activo", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Punto inactivado."));
        assertThat(jdbc.sql("SELECT activo FROM puntos WHERE id = :i").param("i", id).query(Boolean.class).single()).isFalse();
        put("/api/maestros/puntos/" + id + "/estado", coordinador(), Map.of("activo", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Punto activado."));
        EventoAuditoria e = evento("punto_editado", Operacion.ACTUALIZACION, COORD, "Punto " + id + " «");
        assertThat(e.detalle()).containsAnyOf("inactivado", "activado");

        long esmActivo = sembrarUsuario("ESM activo", true, new int[]{7}, null, id, null);
        sembrarUsuario("ESM inactivo (no cuenta)", false, new int[]{7}, null, id, null);
        auditoria.limpiar();
        put("/api/maestros/puntos/" + id + "/estado", coordinador(), Map.of("activo", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("El punto es el alcance de 1 usuarios ESM activos: cámbielos antes de inactivarlo."));
        assertThat(jdbc.sql("SELECT activo FROM puntos WHERE id = :i").param("i", id).query(Boolean.class).single()).isTrue();
        assertThat(auditoria.eventos()).isEmpty();
        put("/api/maestros/puntos/" + id + "/estado", coordinador(), Map.of("activo", true)).andExpect(status().isOk()); // activar siempre se puede

        jdbc.sql("UPDATE usuarios_copia SET activo = false, version = version + 1 WHERE uid = :u").param("u", esmActivo).update();
        put("/api/maestros/puntos/" + id + "/estado", coordinador(), Map.of("activo", false)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM puntos WHERE id = :i").param("i", id).query(Boolean.class).single()).isFalse();
    }

    // ---------------------------------------------------------------- auditores

    @Test
    @DisplayName("auditores: lista de la copia de usuarios con su rol técnico (por defecto «otro»), activos primero, y los roles")
    void listaDeAuditores() throws Exception {
        String sufijo = unico("");
        long inactivo = sembrarAuditor("Auditor Aaa inactivo " + sufijo, false);
        long activo = sembrarAuditor("Auditor Zzz activo " + sufijo, true);
        long noAuditor = sembrarUsuario("Coordinador " + sufijo, true, 2);

        ResultActions r = get("/api/maestros/auditores", coordinador()).andExpect(status().isOk())
                .andExpect(jsonPath("$.auditores[*].id", hasItem(activo))).andExpect(jsonPath("$.auditores[*].id", hasItem(inactivo)))
                .andExpect(jsonPath("$.auditores[?(@.id==" + activo + ")].rol").value(hasItem("otro")))
                .andExpect(jsonPath("$.auditores[?(@.id==" + activo + ")].rolNombre").value(hasItem("Otro")))
                .andExpect(jsonPath("$.auditores[?(@.id==" + activo + ")].usuario").value(hasItem("u" + activo)))
                .andExpect(jsonPath("$.auditores[?(@.id==" + inactivo + ")].activo").value(hasItem(false)))
                .andExpect(jsonPath("$.roles.regente").value("Regente de farmacia"))
                .andExpect(jsonPath("$.roles.quimico_farmaceutico").value("Químico farmacéutico"))
                .andExpect(jsonPath("$.roles.otro").value("Otro"));
        String json = cuerpo(r);
        assertThat(json).doesNotContain("\"id\":" + noAuditor + ",");
        assertThat(json.indexOf("\"id\":" + activo + ",")).as("los activos van primero").isLessThan(json.indexOf("\"id\":" + inactivo + ","));
    }

    @Test
    @DisplayName("editar un auditor: rol y municipio base; 404 si no es auditor; 422 con rol o municipio inválidos; el evento no lleva datos personales")
    void editarAuditor() throws Exception {
        long aud = sembrarAuditor("Auditor a editar", true);
        String rolesMal = "Elija el rol: Químico farmacéutico, Regente de farmacia, Auditor en salud, Ingeniero de sistemas, Otro.";
        put("/api/maestros/auditores/" + aud, coordinador(), Map.of("rol", "brujo")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(rolesMal));
        put("/api/maestros/auditores/" + aud, coordinador(), new HashMap<String, Object>()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(rolesMal));
        put("/api/maestros/auditores/" + aud, coordinador(), Map.of("rol", "regente", "municipioBase", "99999"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("El municipio base no existe."));
        assertThat(auditoria.eventos()).extracting(EventoAuditoria::tipo).doesNotContain("auditor_editado");

        put("/api/maestros/auditores/" + aud, coordinador(), Map.of("rol", "regente", "municipioBase", "11001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Auditor actualizado."));
        Map<String, Object> fila = jdbc.sql("SELECT rol, municipio_base FROM auditores WHERE usuario_id = :i").param("i", aud).query().singleRow();
        assertThat(fila.get("rol")).isEqualTo("regente");
        assertThat(String.valueOf(fila.get("municipio_base")).strip()).isEqualTo("11001");
        get("/api/maestros/auditores", coordinador())
                .andExpect(jsonPath("$.auditores[?(@.id==" + aud + ")].rolNombre").value(hasItem("Regente de farmacia")))
                .andExpect(jsonPath("$.auditores[?(@.id==" + aud + ")].municipioBase").value(hasItem("11001")));
        EventoAuditoria e = evento("auditor_editado", Operacion.ACTUALIZACION, COORD, "Auditor " + aud + ":");
        assertThat(e.detalle()).contains("rol: «otro» → «regente»").doesNotContain(documentoDe(aud)).doesNotContain("visitas-a.prueba.local");

        long coordinadorCopia = sembrarUsuario("Coordinador no auditor", true, 2);
        put("/api/maestros/auditores/" + coordinadorCopia, coordinador(), Map.of("rol", "regente"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El usuario no es auditor."));
        put("/api/maestros/auditores/999999999999", coordinador(), Map.of("rol", "regente"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El usuario no es auditor."));
    }

    // ---------------------------------------------------------------- operador logístico

    @Test
    @DisplayName("operador logístico: validaciones, un solo OPL activo, correos normalizados; el evento no lleva los correos")
    void opl() throws Exception {
        List<Map<String, Object>> antes = jdbc.sql("SELECT id, nombre, nit, correos::text AS correos, activo FROM opl").query().listOfRows();
        try {
            put("/api/maestros/opl", coordinador(), Map.of("nombre", "Operador", "correos", List.of()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.mensaje").value("Registre al menos un correo del operador: recibe la notificación previa (Anexo 3 p.9)."));
            put("/api/maestros/opl", coordinador(), Map.of("nombre", "Op", "correos", List.of("a@example.com")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.mensaje").value("La razón social del operador debe tener de 3 a 200 caracteres."));
            put("/api/maestros/opl", coordinador(), Map.of("nombre", "Operador", "nit", "12-3", "correos", List.of("a@example.com")))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje")
                            .value("El NIT debe tener de 6 a 12 dígitos y, si acaso, el dígito de verificación (900123456-7)."));
            put("/api/maestros/opl", coordinador(), Map.of("nombre", "Operador", "correos", List.of("malo")))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("el correo «malo» no es válido"));
            List<String> once = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                once.add("c" + i + "@example.com");
            }
            put("/api/maestros/opl", coordinador(), Map.of("nombre", "Operador", "correos", once))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("máximo 10 correos"));
            assertThat(auditoria.eventos()).isEmpty();

            Map<String, Object> cuerpo = Map.of("nombre", "Operador logístico de prueba", "nit", "900123456-7",
                    "correos", List.of("Opl.Notificaciones@Example.com; opl.notificaciones@example.com", "opl.dos@example.com"));
            put("/api/maestros/opl", coordinador(), cuerpo).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mensaje").value("Operador logístico actualizado."));
            put("/api/maestros/opl", coordinador(), cuerpo).andExpect(status().isOk()); // otra vez: sigue habiendo uno solo
            get("/api/maestros/opl", coordinador()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nombre").value("Operador logístico de prueba")).andExpect(jsonPath("$.nit").value("900123456-7"))
                    .andExpect(jsonPath("$.correos.length()").value(2)).andExpect(jsonPath("$.correos[0]").value("opl.notificaciones@example.com"))
                    .andExpect(jsonPath("$.id").isNumber());
            assertThat(jdbc.sql("SELECT count(*) FROM opl WHERE activo").query(Long.class).single()).isEqualTo(1L);
            EventoAuditoria e = evento("catalogo_editado", Operacion.ACTUALIZACION, COORD, "Operador logístico actualizado: «Operador logístico de prueba»");
            assertThat(e.detalle()).doesNotContain("example.com");
        } finally {
            jdbc.sql("UPDATE opl SET activo = false WHERE activo").update();
            for (Map<String, Object> o : antes) {
                jdbc.sql("UPDATE opl SET nombre = :n, nit = :nit, correos = CAST(:c AS text[]), activo = :a WHERE id = :id")
                        .param("n", o.get("nombre")).param("nit", o.get("nit"), java.sql.Types.VARCHAR).param("c", o.get("correos"))
                        .param("a", o.get("activo")).param("id", o.get("id")).update();
            }
        }
    }

    @Test
    @DisplayName("sin operador registrado: {id: null, nombre: \"\", nit: \"\", correos: []}")
    void oplSinRegistrar() throws Exception {
        List<Long> activos = jdbc.sql("SELECT id FROM opl WHERE activo").query(Long.class).list();
        try {
            jdbc.sql("UPDATE opl SET activo = false WHERE activo").update();
            get("/api/maestros/opl", coordinador()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.nombre").value("")).andExpect(jsonPath("$.nit").value("")).andExpect(jsonPath("$.correos.length()").value(0));
        } finally {
            for (Long id : activos) {
                jdbc.sql("UPDATE opl SET activo = true WHERE id = :id").param("id", id).update();
            }
        }
    }

    // ---------------------------------------------------------------- autorización

    @Test
    @DisplayName("sin la pantalla MAESTROS: 403 «No tiene permiso para esta acción.» en todo; sin token: 401")
    void autorizacion() throws Exception {
        for (RequestPostProcessor t : List.of(token(7_200_000_002L, "CATALOGOS"), token(7_200_000_003L))) {
            get("/api/maestros/puntos", t).andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
            post("/api/maestros/puntos", t, punto(unico("No debe "), "ESM")).andExpect(status().isForbidden());
            put("/api/maestros/puntos/1", t, punto("No debe", "ESM")).andExpect(status().isForbidden());
            put("/api/maestros/puntos/1/estado", t, Map.of("activo", false)).andExpect(status().isForbidden());
            get("/api/maestros/auditores", t).andExpect(status().isForbidden());
            put("/api/maestros/auditores/1", t, Map.of("rol", "regente")).andExpect(status().isForbidden());
            get("/api/maestros/opl", t).andExpect(status().isForbidden());
            put("/api/maestros/opl", t, Map.of("nombre", "No debe", "correos", List.of("a@example.com"))).andExpect(status().isForbidden());
            get("/api/maestros/obligaciones", t).andExpect(status().isForbidden());
        }
        assertThat(auditoria.eventos()).isEmpty();
        get("/api/maestros/puntos", null).andExpect(status().isUnauthorized());
        put("/api/maestros/opl", null, Map.of("nombre", "No debe", "correos", List.of("a@example.com"))).andExpect(status().isUnauthorized());
    }
}
