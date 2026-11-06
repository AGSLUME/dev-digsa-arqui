package co.com.agsamericas.digsa.visitas.catalogos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
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

/**
 * VA1.3: paridad de {@code /api/catalogos/**} con el monolito (CatalogosApiTest de digsa-java; Anexo 5, prueba 4). Autoriza la pantalla
 * CATALOGOS del token, los errores son RFC 7807 con {@code mensaje} y la bitácora es un evento de auditoría. La base se comparte: los
 * datos llevan sufijo único y los parámetros que se cambian se restauran.
 */
class CatalogosApiTest extends PruebaApi {

    private static final long COORD = 7_100_000_001L;
    private static final String SIN_PERMISO = "No tiene permiso para esta acción.";

    private static org.springframework.test.web.servlet.request.RequestPostProcessor coordinador() {
        return token(COORD, "CATALOGOS");
    }

    private static Map<String, Object> fuerza(String codigo, String nombre) {
        Map<String, Object> m = new HashMap<>();
        m.put("codigo", codigo);
        m.put("nombre", nombre);
        m.put("sanidad", "Sanidad de prueba");
        m.put("correos", List.of("sanidad.prueba@example.com"));
        return m;
    }

    private static void validacion(ResultActions r, String mensaje) throws Exception {
        r.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value(mensaje));
    }

    private static void validacionContiene(ResultActions r, String... partes) throws Exception {
        r.andExpect(status().isUnprocessableEntity());
        String m = cuerpo(r);
        for (String p : partes) {
            assertThat(m).contains(p);
        }
    }

    // ---------------------------------------------------------------- lectura

    @Test
    @DisplayName("datos iniciales: 4 fuerzas, documentos C/T/R/V, facturas 1-4, 8 regionales, 185 puntos y 1122 municipios")
    void datosIniciales() throws Exception {
        ResultActions r = get("/api/catalogos", coordinador()).andExpect(status().isOk());
        r.andExpect(jsonPath("$.fuerzas[*].codigo", hasItems("EJC", "ARC", "FAC", "NA")));
        r.andExpect(jsonPath("$.catalogos[*].valores[*].descripcion", hasItems("Cédula de ciudadanía", "Visa", "Acuerdo", "Requisición")));
        r.andExpect(jsonPath("$.regionales[*].nombre",
                hasItems("Bogotá", "Medellín", "Cali", "Barranquilla", "Bucaramanga", "Cartagena", "Ibagué", "Neiva")));
        r.andExpect(jsonPath("$.unidades[*].codigo", hasItems("ESM", "FA", "NC")));
        r.andExpect(jsonPath("$.modalidades").isArray());
        r.andExpect(jsonPath("$.tiposVisita.length()").value(9));
        r.andExpect(jsonPath("$.tiposVisita[0].aviso").exists());
        r.andExpect(jsonPath("$.departamentos").isNotEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial'").query(Long.class).single()).isEqualTo(185L);
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial' AND unidad = 'FA'").query(Long.class).single())
                .isEqualTo(10L);
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE exige_qf").query(Long.class).single()).isGreaterThanOrEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM par_municipios").query(Long.class).single()).isEqualTo(1122L);
    }

    @Test
    @DisplayName("D6: los parámetros son solo los de visitas (sin presupuesto_*, bandeja_retencion_dias ni alertas_bloqueo_cuentas)")
    void parametrosSoloDeVisitas() throws Exception {
        get("/api/catalogos", coordinador()).andExpect(status().isOk())
                .andExpect(jsonPath("$.parametros[*].clave", hasItems("aviso_umbral_opl", "km_mismo_dia", "correo_digsa", "inicio_contrato")))
                .andExpect(jsonPath("$.parametros[*].clave", everyItem(not(startsWith("presupuesto_")))))
                .andExpect(jsonPath("$.parametros[*].clave", not(hasItem("bandeja_retencion_dias"))))
                .andExpect(jsonPath("$.parametros[*].clave", not(hasItem("alertas_bloqueo_cuentas"))))
                .andExpect(jsonPath("$.parametros[0].editable").value(true));
    }

    // ---------------------------------------------------------------- fuerzas

    @Test
    @DisplayName("prueba 4: crear, modificar y eliminar una fuerza en vivo; cada cambio publica su evento de auditoría")
    void fuerzaEnVivo() throws Exception {
        String codigo = letras(5);
        post("/api/catalogos/fuerzas", coordinador(), fuerza(codigo.toLowerCase(), "Policía Nacional de prueba"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value("Fuerza creada."));
        get("/api/catalogos", coordinador()).andExpect(jsonPath("$.fuerzas[*].codigo", hasItem(codigo)));
        assertThat(jdbc.sql("SELECT activo FROM par_fuerzas WHERE codigo = :c").param("c", codigo).query(Boolean.class).single())
                .as("el código se pasa a mayúsculas y queda activa").isTrue();
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza(codigo, "Otra")), "Ya existe una fuerza con el código " + codigo + ".");

        Map<String, Object> editada = fuerza("IGNORADO", "Policía Nacional (editada)");
        put("/api/catalogos/fuerzas/" + codigo, coordinador(), editada)
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Fuerza actualizada."));
        assertThat(jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = :c").param("c", codigo).query(String.class).single())
                .isEqualTo("Policía Nacional (editada)");

        delete("/api/catalogos/fuerzas/" + codigo, coordinador())
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Fuerza eliminada."));
        get("/api/catalogos", coordinador()).andExpect(jsonPath("$.fuerzas[*].codigo", not(hasItem(codigo))));

        EventoAuditoria creada = evento("fuerza_creada", Operacion.INSERCION, COORD, "Fuerza " + codigo + " creada");
        assertThat(creada.detalle()).contains("«Policía Nacional de prueba»");
        EventoAuditoria cambiada = evento("fuerza_editada", Operacion.ACTUALIZACION, COORD, "Fuerza " + codigo + ":");
        assertThat(cambiada.detalle()).contains("nombre: «Policía Nacional de prueba» → «Policía Nacional (editada)»");
        evento("fuerza_eliminada", Operacion.ELIMINACION, COORD, "Fuerza " + codigo + " eliminada");
        assertThat(auditoria.eventos()).extracting(EventoAuditoria::detalle).noneMatch(d -> d != null && d.contains("sanidad.prueba@example.com"));
    }

    @Test
    @DisplayName("validaciones de la fuerza: código de 2 a 5 letras, nombre, sanidad, correos; las fallidas no publican eventos")
    void validacionesFuerza() throws Exception {
        String codigoMalo = "El código de la fuerza debe tener de 2 a 5 letras mayúsculas (p. ej. EJC).";
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza("P1", "Con número")), codigoMalo);
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza("ABCDEF", "Muy largo")), codigoMalo);
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza("", "Vacío")), codigoMalo);
        validacion(post("/api/catalogos/fuerzas", coordinador(), new HashMap<String, Object>()), codigoMalo);
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza("PQ", "X")), "El nombre de la fuerza debe tener de 2 a 120 caracteres.");
        validacion(post("/api/catalogos/fuerzas", coordinador(), fuerza("PQ", "N".repeat(121))),
                "El nombre de la fuerza debe tener de 2 a 120 caracteres.");
        Map<String, Object> sanidadLarga = fuerza("PQ", "Sanidad larga");
        sanidadLarga.put("sanidad", "S".repeat(151));
        validacion(post("/api/catalogos/fuerzas", coordinador(), sanidadLarga), "La sanidad (DISAN o JEFSA) tiene máximo 150 caracteres.");
        Map<String, Object> malCorreo = fuerza("PQ", "Correo malo");
        malCorreo.put("correos", List.of("no-es-correo"));
        validacion(post("/api/catalogos/fuerzas", coordinador(), malCorreo), "El correo «no-es-correo» no es válido.");
        List<String> once = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            once.add("correo" + i + "@example.com");
        }
        Map<String, Object> muchos = fuerza("PQ", "Muchos correos");
        muchos.put("correos", once);
        validacion(post("/api/catalogos/fuerzas", coordinador(), muchos), "Máximo 10 correos.");
        assertThat(jdbc.sql("SELECT count(*) FROM par_fuerzas WHERE codigo IN ('P1','ABCDEF','PQ')").query(Long.class).single()).isZero();
        assertThat(auditoria.eventos()).extracting(EventoAuditoria::tipo).doesNotContain("fuerza_creada");
    }

    @Test
    @DisplayName("una fuerza en uso por puntos no se elimina (se inactiva y los puntos la conservan); 404 si no existe")
    void fuerzaEnUsoPorPuntos() throws Exception {
        validacionContiene(delete("/api/catalogos/fuerzas/EJC", coordinador()), "La fuerza EJC está en uso (", "Inactívela en lugar de eliminarla.");

        String codigo = letras(4);
        post("/api/catalogos/fuerzas", coordinador(), fuerza(codigo, "Fuerza con punto")).andExpect(status().isCreated());
        jdbc.sql("INSERT INTO puntos (nombre, unidad, fuerza, creado_por) VALUES (:n, 'FA', :f, 'prueba')")
                .param("n", unico("Punto de fuerza en uso ")).param("f", codigo).update();
        validacion(delete("/api/catalogos/fuerzas/" + codigo, coordinador()),
                "La fuerza " + codigo + " está en uso (1 puntos o usuarios). Inactívela en lugar de eliminarla.");
        Map<String, Object> inactiva = fuerza(codigo, "Fuerza con punto");
        inactiva.put("activo", false);
        put("/api/catalogos/fuerzas/" + codigo, coordinador(), inactiva).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM par_fuerzas WHERE codigo = :c").param("c", codigo).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE fuerza = :c").param("c", codigo).query(Long.class).single()).isEqualTo(1L);
        Map<String, Object> sinActivo = fuerza(codigo, "Fuerza con punto");
        put("/api/catalogos/fuerzas/" + codigo, coordinador(), sinActivo).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM par_fuerzas WHERE codigo = :c").param("c", codigo).query(Boolean.class).single())
                .as("activo nulo cuenta como true").isTrue();

        put("/api/catalogos/fuerzas/ZZNOEX", coordinador(), fuerza("ZZNOEX", "No existe"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("La fuerza no existe."));
        delete("/api/catalogos/fuerzas/ZZNOEX", coordinador())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("La fuerza no existe."));
    }

    @Test
    @DisplayName("una fuerza en uso por usuarios (usuarios_copia.alcance_fuerza) no se elimina; sin usuarios sí")
    void fuerzaEnUsoPorUsuarios() throws Exception {
        String codigo = letras(5);
        post("/api/catalogos/fuerzas", coordinador(), fuerza(codigo, "Fuerza con usuario")).andExpect(status().isCreated());
        long uid = sembrarUsuario("Usuario de fuerza", true, new int[]{6}, codigo, null, null);
        validacion(delete("/api/catalogos/fuerzas/" + codigo, coordinador()),
                "La fuerza " + codigo + " está en uso (1 puntos o usuarios). Inactívela en lugar de eliminarla.");
        jdbc.sql("UPDATE usuarios_copia SET alcance_fuerza = NULL, version = version + 1 WHERE uid = :u").param("u", uid).update();
        delete("/api/catalogos/fuerzas/" + codigo, coordinador()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- valores de catálogo

    @Test
    @DisplayName("prueba 4: valores de tipo de documento y de factura: crear, editar y eliminar con el formato del catálogo")
    void valoresEnVivo() throws Exception {
        String codigo = letras(3);
        ResultActions c = post("/api/catalogos/tipo_documento/valores", coordinador(),
                Map.of("codigo", codigo.toLowerCase(), "descripcion", "Documento de prueba"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value("Valor creado."));
        long id = idCreado(c);
        assertThat(jdbc.sql("SELECT codigo FROM catalogo_valores WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo(codigo);

        validacionContiene(post("/api/catalogos/tipo_documento/valores", coordinador(), Map.of("codigo", codigo, "descripcion", "Repetido")),
                "Ya existe el código " + codigo + " en ");
        validacionContiene(post("/api/catalogos/tipo_documento/valores", coordinador(), Map.of("codigo", "12", "descripcion", "Números")),
                "El código «12» no tiene el formato de ", " (de 1 a 3 letras mayúsculas, p. ej. C).");
        validacionContiene(post("/api/catalogos/tipo_factura/valores", coordinador(), Map.of("codigo", "AB", "descripcion", "Letras")),
                "El código «AB» no tiene el formato de ", " (de 1 a 3 dígitos, p. ej. 1).");
        validacion(post("/api/catalogos/tipo_documento/valores", coordinador(), Map.of("codigo", letras(3), "descripcion", "X")),
                "La descripción debe tener de 2 a 120 caracteres.");

        put("/api/catalogos/valores/" + id, coordinador(), Map.of("codigo", codigo, "descripcion", "Documento editado"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Valor actualizado."));
        delete("/api/catalogos/valores/" + id, coordinador())
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Valor eliminado."));
        assertThat(jdbc.sql("SELECT count(*) FROM catalogo_valores WHERE id = :id").param("id", id).query(Long.class).single()).isZero();

        evento("catalogo_creado", Operacion.INSERCION, COORD, "valor " + codigo + " «Documento de prueba» creado");
        EventoAuditoria e = evento("catalogo_editado", Operacion.ACTUALIZACION, COORD, "valor " + codigo + ":");
        assertThat(e.detalle()).contains("«Documento de prueba» → «Documento editado»");
        evento("catalogo_eliminado", Operacion.ELIMINACION, COORD, "valor " + codigo + " «Documento editado» eliminado");
    }

    @Test
    @DisplayName("valores: 404 de catálogo y de valor; fuerzas y regionales no son catálogos parametrizables; activo nulo conserva el actual")
    void valoresNoEncontradosYActivo() throws Exception {
        for (String inexistente : new String[]{"no_existe", "fuerzas", "regionales"}) {
            post("/api/catalogos/" + inexistente + "/valores", coordinador(), Map.of("codigo", "ZQ", "descripcion", "Valor de prueba"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El catálogo no existe."));
        }
        put("/api/catalogos/valores/999999999", coordinador(), Map.of("codigo", "ZQ", "descripcion", "Valor de prueba"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El valor no existe."));
        delete("/api/catalogos/valores/999999999", coordinador())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El valor no existe."));

        String codigo = letras(3);
        long id = idCreado(post("/api/catalogos/tipo_documento/valores", coordinador(), Map.of("codigo", codigo, "descripcion", "Valor activo"))
                .andExpect(status().isCreated()));
        put("/api/catalogos/valores/" + id, coordinador(), Map.of("codigo", codigo, "descripcion", "Valor activo", "activo", false))
                .andExpect(status().isOk());
        put("/api/catalogos/valores/" + id, coordinador(), Map.of("codigo", codigo, "descripcion", "Valor sin activo")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM catalogo_valores WHERE id = :i").param("i", id).query(Boolean.class).single())
                .as("activo nulo conserva el actual (false)").isFalse();
        validacion(put("/api/catalogos/valores/" + id, coordinador(), Map.of("codigo", codigo, "descripcion", "Y")),
                "La descripción debe tener de 2 a 120 caracteres.");
    }

    // ---------------------------------------------------------------- regionales

    @Test
    @DisplayName("regionales: crear y renombrar sin repetir, inactivar (nunca se borra), 404 y eventos")
    void regionales() throws Exception {
        String nombre = unico("Regional prueba ");
        ResultActions c = post("/api/catalogos/regionales", coordinador(), Map.of("nombre", nombre))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value("Regional creada."));
        long id = idCreado(c);
        validacion(post("/api/catalogos/regionales", coordinador(), Map.of("nombre", nombre)), "Ya existe la regional «" + nombre + "».");
        validacion(post("/api/catalogos/regionales", coordinador(), Map.of("nombre", "R")),
                "El nombre de la regional debe tener de 2 a 80 caracteres.");
        validacion(post("/api/catalogos/regionales", coordinador(), Map.of("nombre", "R".repeat(81))),
                "El nombre de la regional debe tener de 2 a 80 caracteres.");

        put("/api/catalogos/regionales/" + id, coordinador(), Map.of("nombre", nombre + " B", "activo", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Regional actualizada."));
        assertThat(jdbc.sql("SELECT activo FROM par_regionales WHERE id = :i").param("i", id).query(Boolean.class).single()).isFalse();
        put("/api/catalogos/regionales/" + id, coordinador(), Map.of("nombre", nombre + " B")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM par_regionales WHERE id = :i").param("i", id).query(Boolean.class).single())
                .as("activo nulo cuenta como true").isTrue();
        validacion(put("/api/catalogos/regionales/" + id, coordinador(), Map.of("nombre", "Bogotá")), "Ya existe la regional «Bogotá».");
        put("/api/catalogos/regionales/999999999", coordinador(), Map.of("nombre", "No existe"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("La regional no existe."));

        evento("regional_creada", Operacion.INSERCION, COORD, "Regional «" + nombre + "» creada");
        EventoAuditoria e = evento("regional_editada", Operacion.ACTUALIZACION, COORD, "Regional " + id + ":");
        assertThat(e.detalle()).contains("nombre: «" + nombre + "» → «" + nombre + " B»", "activo: «true» → «false»");
    }

    @Test
    @DisplayName("una regional con usuarios OPL regional activos (usuarios_copia.alcance_regional) no se inactiva; con inactivos sí")
    void regionalConUsuarios() throws Exception {
        String nombre = unico("Regional con usuarios ");
        long id = idCreado(post("/api/catalogos/regionales", coordinador(), Map.of("nombre", nombre)).andExpect(status().isCreated()));
        long activo = sembrarUsuario("OPL regional activo", true, new int[]{9}, null, null, id);
        sembrarUsuario("OPL regional inactivo (no cuenta)", false, new int[]{9}, null, null, id);
        validacion(put("/api/catalogos/regionales/" + id, coordinador(), Map.of("nombre", nombre, "activo", false)),
                "La regional tiene 1 usuarios OPL regional activos: cámbielos antes de inactivarla.");
        assertThat(jdbc.sql("SELECT activo FROM par_regionales WHERE id = :i").param("i", id).query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE usuarios_copia SET activo = false, version = version + 1 WHERE uid = :u").param("u", activo).update();
        put("/api/catalogos/regionales/" + id, coordinador(), Map.of("nombre", nombre, "activo", false)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT activo FROM par_regionales WHERE id = :i").param("i", id).query(Boolean.class).single()).isFalse();
    }

    // ---------------------------------------------------------------- parámetros

    @Test
    @DisplayName("parámetros con rango: mensajes del monolito, regla fija del Anexo 3, 404, y evento con el antes y el después")
    void parametros() throws Exception {
        String antes = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'aviso_umbral_opl'").query(String.class).single();
        String nuevo = "6".equals(antes) ? "7" : "6";
        try {
            validacion(put("/api/catalogos/parametros/aviso_umbral_opl", coordinador(), Map.of("valor", "16")),
                    "El valor de «aviso_umbral_opl» debe ser un número entre 0 y 15.");
            validacion(put("/api/catalogos/parametros/aviso_umbral_opl", coordinador(), Map.of("valor", "texto")),
                    "El valor de «aviso_umbral_opl» debe ser un número entre 0 y 15.");
            validacion(put("/api/catalogos/parametros/visitas_por_dia", coordinador(), Map.of("valor", "0")),
                    "El valor de «visitas_por_dia» debe ser un número entre 1 y 6.");
            validacion(put("/api/catalogos/parametros/notif_dias_opl", coordinador(), Map.of("valor", "10")),
                    "El parámetro «notif_dias_opl» es una regla fija del Anexo 3 y no se puede cambiar.");
            put("/api/catalogos/parametros/no_existe", coordinador(), Map.of("valor", "1"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("El parámetro no existe."));
            validacion(put("/api/catalogos/parametros/inicio_contrato", coordinador(), Map.of("valor", "2026-02-30")),
                    "La fecha de inicio del contrato no es válida (AAAA-MM-DD).");
            assertThat(auditoria.eventos()).extracting(EventoAuditoria::tipo).doesNotContain("parametro_editado");

            put("/api/catalogos/parametros/aviso_umbral_opl", coordinador(), Map.of("valor", nuevo))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Parámetro actualizado."));
            assertThat(jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'aviso_umbral_opl'").query(String.class).single()).isEqualTo(nuevo);
            EventoAuditoria e = evento("parametro_editado", Operacion.ACTUALIZACION, COORD, "Parámetro aviso_umbral_opl:");
            assertThat(e.detalle()).contains("«" + antes + "» → «" + nuevo + "»");
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'aviso_umbral_opl'").param("v", antes).update();
        }
    }

    @Test
    @DisplayName("correo_digsa: normaliza a «a, b» sin repetidos, valida y el evento dice «modificado» sin los correos")
    void parametroCorreoDigsa() throws Exception {
        String antes = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).optional().orElse("");
        try {
            put("/api/catalogos/parametros/correo_digsa", coordinador(),
                    Map.of("valor", "Ana@Digsa.prueba.local; beto@digsa.prueba.local  ana@digsa.prueba.local"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Parámetro actualizado."));
            assertThat(jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).single())
                    .isEqualTo("ana@digsa.prueba.local, beto@digsa.prueba.local");
            EventoAuditoria e = evento("parametro_editado", Operacion.ACTUALIZACION, COORD, "Parámetro correo_digsa");
            assertThat(e.detalle()).contains("modificado").doesNotContain("digsa.prueba.local");

            validacion(put("/api/catalogos/parametros/correo_digsa", coordinador(), Map.of("valor", "malo")), "El correo «malo» no es válido.");
            validacion(put("/api/catalogos/parametros/correo_digsa", coordinador(), Map.of("valor", "a".repeat(501))),
                    "Los correos de la DIGSA tienen máximo 500 caracteres.");
            List<String> once = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                once.add("c" + i + "@digsa.prueba.local");
            }
            validacion(put("/api/catalogos/parametros/correo_digsa", coordinador(), Map.of("valor", String.join(",", once))), "Máximo 10 correos.");
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'correo_digsa'").param("v", antes).update();
        }
    }

    // ---------------------------------------------------------------- autorización

    @Test
    @DisplayName("sin la pantalla CATALOGOS: 403 en todo salvo municipios; sin token: 401")
    void autorizacion() throws Exception {
        var soloMaestros = token(7_100_000_002L, "MAESTROS");
        var sinPantallas = token(7_100_000_003L);
        for (var t : List.of(soloMaestros, sinPantallas)) {
            get("/api/catalogos", t).andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
            post("/api/catalogos/fuerzas", t, fuerza("ZZ", "No debe")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
            put("/api/catalogos/fuerzas/EJC", t, fuerza("EJC", "No debe")).andExpect(status().isForbidden());
            delete("/api/catalogos/fuerzas/EJC", t).andExpect(status().isForbidden());
            post("/api/catalogos/tipo_documento/valores", t, Map.of("codigo", "ZZ", "descripcion", "No debe")).andExpect(status().isForbidden());
            put("/api/catalogos/valores/1", t, Map.of("codigo", "ZZ", "descripcion", "No debe")).andExpect(status().isForbidden());
            delete("/api/catalogos/valores/1", t).andExpect(status().isForbidden());
            post("/api/catalogos/regionales", t, Map.of("nombre", "No debe")).andExpect(status().isForbidden());
            put("/api/catalogos/regionales/1", t, Map.of("nombre", "No debe")).andExpect(status().isForbidden());
            put("/api/catalogos/parametros/aviso_umbral_opl", t, Map.of("valor", "5")).andExpect(status().isForbidden());
            get("/api/catalogos/municipios?departamento=05", t).andExpect(status().isOk());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM par_fuerzas WHERE codigo = 'ZZ'").query(Long.class).single()).isZero();
        assertThat(auditoria.eventos()).extracting(EventoAuditoria::tipo).doesNotContain("fuerza_creada", "regional_creada", "catalogo_creado");

        get("/api/catalogos", null).andExpect(status().isUnauthorized());
        post("/api/catalogos/fuerzas", null, fuerza("ZZ", "No debe")).andExpect(status().isUnauthorized());
        get("/api/catalogos/municipios?departamento=05", null).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("municipios: solo exige token de persona; ordenados por nombre; 422 «Elija el departamento.» si no son dos dígitos")
    void municipios() throws Exception {
        var persona = token(7_100_000_004L);
        ResultActions r = get("/api/catalogos/municipios?departamento=05", persona).andExpect(status().isOk());
        r.andExpect(jsonPath("$[*].codigo", hasItem("05001"))).andExpect(jsonPath("$[0].nombre").isString());
        List<String> codigos = jdbc.sql("SELECT codigo FROM par_municipios WHERE departamento = '05' ORDER BY nombre").query(String.class).list();
        r.andExpect(jsonPath("$.length()").value(codigos.size())).andExpect(jsonPath("$[0].codigo").value(codigos.get(0)));
        for (String malo : new String[]{"5", "055", "ab", " "}) {
            validacion(get("/api/catalogos/municipios?departamento=" + malo.replace(" ", "%20"), persona), "Elija el departamento.");
        }
    }
}
