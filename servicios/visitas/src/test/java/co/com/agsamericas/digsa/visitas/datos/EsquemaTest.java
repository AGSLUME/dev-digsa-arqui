package co.com.agsamericas.digsa.visitas.datos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * VA1.2 (sin transacción: cada violación esperada abortaría la transacción de la prueba; los datos llevan sufijo único): las migraciones
 * V1..V8 de {@code visitas} conservan las reglas de datos del monolito (V3, V4, V6, V7, V8, V9 archivos, V12, V13, V16 y R__catalogos).
 */
class EsquemaTest extends PruebaIntegracion {

    private static final AtomicLong SECUENCIA = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    // ---------------------------------------------------------------- semilla

    @Test
    void laSemillaTrae1122MunicipiosYLasFuerzasConSuSanidad() {
        assertThat(count("SELECT count(*) FROM par_municipios")).isEqualTo(1122L);
        assertThat(jdbc.sql("SELECT codigo FROM par_fuerzas WHERE codigo IN ('EJC','ARC','FAC','NA') ORDER BY codigo")
                .query(String.class).list()).containsExactly("ARC", "EJC", "FAC", "NA");
        assertThat(count("SELECT count(*) FROM par_fuerzas WHERE codigo IN ('EJC','ARC','FAC') AND sanidad IS NOT NULL")).isEqualTo(3L);
        assertThat(count("SELECT count(*) FROM par_fuerzas WHERE codigo = 'NA' AND sanidad IS NULL")).isEqualTo(1L);
    }

    @Test
    void losCatalogosDeDocumentoYFacturaTraenSusValores() {
        assertThat(jdbc.sql("SELECT codigo FROM catalogo_valores WHERE catalogo = 'tipo_documento' AND codigo IN ('C','T','R','V') ORDER BY codigo")
                .query(String.class).list()).containsExactly("C", "R", "T", "V");
        assertThat(jdbc.sql("SELECT codigo || ':' || descripcion FROM catalogo_valores WHERE catalogo = 'tipo_factura' "
                + "AND codigo IN ('1','2','3','4') ORDER BY codigo").query(String.class).list())
                .containsExactly("1:Acuerdo", "2:CTC", "3:Tutela", "4:Requisición");
    }

    @Test
    void laSemillaTraeOchoRegionalesNueveTiposDeVisitaYLas185SedesIniciales() {
        assertThat(jdbc.sql("SELECT nombre FROM par_regionales ORDER BY nombre").query(String.class).list())
                // La base se comparte: otras pruebas crean regionales; la semilla son estas 8
                .contains("Bogotá", "Medellín", "Cali", "Barranquilla", "Bucaramanga", "Cartagena", "Ibagué", "Neiva");
        assertThat(jdbc.sql("SELECT codigo FROM par_tipos_visita").query(String.class).list())
                .containsExactlyInAnyOrder("inicial", "anual", "anexo", "riesgo", "necesidad", "requerimiento", "farmacia", "central", "plan");
        assertThat(count("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial'")).isEqualTo(185L);
        assertThat(count("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial' AND unidad = 'FA'")).isEqualTo(10L);
        assertThat(count("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial' AND exige_qf")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void elChecklistV6EsLaUnicaVersionVigenteYElIndiceParcialLoGarantiza() {
        assertThat(jdbc.sql("SELECT version FROM chk_versiones WHERE vigente").query(String.class).list()).containsExactly("v6");
        String def = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'visitas' AND tablename = 'chk_versiones' "
                + "AND indexname = 'uq_chk_versiones_vigente'").query(String.class).single();
        assertThat(def).containsIgnoringCase("UNIQUE").contains("(vigente)").containsIgnoringCase("WHERE vigente");
    }

    @Test
    void losParametrosDeVisitasEstanYLosDePresupuestoYSeguridadNo() {
        List<String> claves = jdbc.sql("SELECT clave FROM par_parametros").query(String.class).list();
        assertThat(claves).contains("notif_habiles_modulo", "inicio_contrato", "aviso_umbral_opl", "aviso_umbral_modulo", "notif_dias_opl",
                "inicial_meses", "max_seguimientos", "correo_digsa", "necesidad_habiles", "recordatorio_seguimiento_habiles",
                "visitas_por_dia", "km_mismo_dia",
                "riesgo_peso_incumplimiento", "riesgo_peso_recurrencia", "riesgo_peso_inmediata", "riesgo_peso_planes",
                "riesgo_peso_incidencias", "riesgo_nivel_alto", "riesgo_nivel_medio", "riesgo_estado_pesos");
        // Decisión D6: lo de presupuesto y lo de la bandeja y las cuentas no es de visitas
        assertThat(claves).noneMatch(c -> c.startsWith("presupuesto_"));
        assertThat(claves).doesNotContain("bandeja_retencion_dias", "alertas_bloqueo_cuentas");
    }

    // ---------------------------------------------------------------- CHECK y unicidad de maestros

    @Test
    void elCodigoDeUnaFuerzaSonDosACincoLetrasMayusculas() {
        for (String malo : new String[]{"e", "ej", "EJ1", "ABCDEF", "A-B", ""}) {
            assertThatThrownBy(() -> fuerza(malo)).as("codigo '%s'", malo).isInstanceOf(DataIntegrityViolationException.class);
        }
        String ok = letras(5);
        fuerza(ok);
        assertThat(count("SELECT count(*) FROM par_fuerzas WHERE codigo = '" + ok + "'")).isEqualTo(1L);
    }

    @Test
    void unEsmSinModalidadFalla() {
        assertThatThrownBy(() -> punto("Punto ESM sin modalidad " + sufijo(), "ESM", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        punto("Punto FA sin modalidad " + sufijo(), "FA", null, null); // solo el ESM exige modalidad
        punto("Punto ESM con modalidad " + sufijo(), "ESM", "Dispensación", null);
    }

    @Test
    void elNombreEsUnicoPorUnidadYLaSiglaEsUnica() {
        String nombre = "Punto duplicado " + sufijo();
        punto(nombre, "FA", null, null);
        assertThatThrownBy(() -> punto(nombre, "FA", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        punto(nombre, "NC", null, null); // el mismo nombre en otra unidad sí
        String sigla = "ST" + sufijo();
        punto("Punto sigla uno " + sufijo(), "FA", null, sigla);
        assertThatThrownBy(() -> punto("Punto sigla dos " + sufijo(), "FA", null, sigla)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- visitas

    @Test
    void elNumeroDeVisitaTieneElFormatoVsAaaammddNnnn() {
        long u = usuario();
        long p = puntoExistente();
        for (String malo : new String[]{"vs-20261106-0001", "VS-2026116-0001", "VS-20261106-001", "VS20261106-0001", "XX-20261106-0001"}) {
            assertThatThrownBy(() -> visita("anual", p, u, malo, null, null, null)).as(malo).isInstanceOf(DataIntegrityViolationException.class);
        }
        String bueno = "VS-20261106-" + String.format("%05d", SECUENCIA.incrementAndGet() % 100000); // admite más de 4 dígitos
        visita("anual", p, u, bueno, null, null, null);
        assertThatThrownBy(() -> visita("anual", p, u, bueno, null, null, null)).isInstanceOf(DataIntegrityViolationException.class); // único
    }

    @Test
    void unEstadoFueraDeLaListaFalla() {
        long u = usuario();
        long p = puntoExistente();
        assertThatThrownBy(() -> visitaConEstado(p, u, "inventado")).isInstanceOf(DataIntegrityViolationException.class);
        for (String e : new String[]{"programada", "notificada", "reprogramada", "ejecucion", "cerrada", "cancelada"}) {
            visitaConEstado(p, u, e);
        }
    }

    @Test
    void unaVisitaANecesidadExigeLaFechaDeSolicitud() {
        long u = usuario();
        long p = puntoExistente();
        assertThatThrownBy(() -> visita("necesidad", p, u, null, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        visita("necesidad", p, u, null, "2026-11-02", null, null);
    }

    @Test
    void elOrigenSoloVaConSeguimientoYSoloUnSeguimientoVigentePorOrigen() {
        long u = usuario();
        long p = puntoExistente();
        long origen = visita("anual", p, u, null, null, null, null);
        // origen sin número de seguimiento y viceversa
        assertThatThrownBy(() -> visita("plan", p, u, null, null, origen, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> visita("plan", p, u, null, null, null, 1)).isInstanceOf(DataIntegrityViolationException.class);
        // origen solo con el tipo plan
        assertThatThrownBy(() -> visita("anual", p, u, null, null, origen, 1)).isInstanceOf(DataIntegrityViolationException.class);
        // seguimiento_n >= 1
        assertThatThrownBy(() -> visita("plan", p, u, null, null, origen, 0)).isInstanceOf(DataIntegrityViolationException.class);
        long primero = visita("plan", p, u, null, null, origen, 1);
        assertThat(primero).isNotEqualTo(origen);
        // un solo seguimiento vigente por origen
        assertThatThrownBy(() -> visita("plan", p, u, null, null, origen, 2)).isInstanceOf(DataIntegrityViolationException.class);
        // al cancelar el vigente cabe otro
        jdbc.sql("UPDATE visitas SET estado = 'cancelada' WHERE id = :i").param("i", primero).update();
        visita("plan", p, u, null, null, origen, 2);
        // una visita no es su propio origen
        assertThatThrownBy(() -> jdbc.sql("UPDATE visitas SET origen_id = id, seguimiento_n = 1, tipo = 'plan' WHERE id = :i")
                .param("i", origen).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unaVisitaTieneUnSoloLiderYElLiderEsElOrdenCero() {
        long lider = usuario();
        long apoyo1 = usuario();
        long apoyo2 = usuario();
        long v = visita("anual", puntoExistente(), lider, null, null, null, null);
        equipo(v, lider, "lider", 0);
        assertThatThrownBy(() -> equipo(v, apoyo1, "lider", 0)).isInstanceOf(DataIntegrityViolationException.class); // segundo líder
        assertThatThrownBy(() -> equipo(v, apoyo1, "lider", 1)).isInstanceOf(DataIntegrityViolationException.class); // líder con orden distinto de 0
        assertThatThrownBy(() -> equipo(v, apoyo1, "apoyo", 0)).isInstanceOf(DataIntegrityViolationException.class); // apoyo con orden 0
        assertThatThrownBy(() -> equipo(v, apoyo1, "apoyo", 6)).isInstanceOf(DataIntegrityViolationException.class); // fuera de 0..5
        assertThatThrownBy(() -> equipo(v, apoyo1, "otro", 1)).isInstanceOf(DataIntegrityViolationException.class);
        equipo(v, apoyo1, "apoyo", 1);
        assertThatThrownBy(() -> equipo(v, apoyo2, "apoyo", 1)).isInstanceOf(DataIntegrityViolationException.class); // orden repetido
        equipo(v, apoyo2, "apoyo", 2);
    }

    // ---------------------------------------------------------------- permisos del rol de la aplicación

    @Test
    void elHistorialDeLaVisitaSoloSeLeeEInserta() {
        long u = usuario();
        long v = visita("anual", puntoExistente(), u, null, null, null, null);
        long h = jdbc.sql("INSERT INTO visitas_historial (visita_id, usuario_id, usuario, evento, detalle) VALUES (:v, :u, 'u', 'creada', 'x') RETURNING id")
                .param("v", v).param("u", u).query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE visitas_historial SET detalle = 'alterado' WHERE id = :h").param("h", h).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM visitas_historial WHERE id = :h").param("h", h).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
    }

    @Test
    void lasTablasMaestrasNoSeBorran() {
        for (String tabla : new String[]{"puntos", "par_regionales", "par_parametros", "auditores", "opl", "catalogos"}) {
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + tabla + " WHERE false").update())
                    .as("DELETE en %s", tabla).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        }
        // Lo que sí concede el monolito sobre los parámetros: SELECT, INSERT y UPDATE
        assertThatCode(() -> jdbc.sql("UPDATE par_parametros SET valor = valor WHERE clave = 'km_mismo_dia'").update()).doesNotThrowAnyException();
    }

    /** V10__ajuste_permisos: la app solo edita parámetros y catálogos existentes y no escribe los festivos (revisión de seguridad, BAJA 9). */
    @Test
    void laAppNoInsertaParametrosNiCatalogosNiEscribeFestivos() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO par_parametros (clave, valor) VALUES ('clave_de_prueba_v10', 'x')").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO catalogos (codigo, nombre) VALUES ('cat_prueba_v10', 'Prueba')").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO par_festivos (fecha, motivo) VALUES (DATE '2099-01-01', 'prueba')").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("UPDATE par_festivos SET motivo = 'x' WHERE false").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM par_festivos WHERE false").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
    }

    @Test
    void laAppSiLeeFestivosYSiEditaParametrosYCatalogosExistentes() {
        assertThatCode(() -> jdbc.sql("SELECT count(*) FROM par_festivos").query(Long.class).single()).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.sql("UPDATE par_parametros SET valor = valor WHERE clave = 'km_mismo_dia'").update()).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.sql("UPDATE catalogos SET nombre = nombre WHERE false").update()).doesNotThrowAnyException();
    }

    @Test
    void lasFuerzasYLosValoresDeCatalogoSiSePuedenBorrar() {
        String f = letras(5);
        fuerza(f);
        assertThat(jdbc.sql("DELETE FROM par_fuerzas WHERE codigo = :c").param("c", f).update()).isEqualTo(1);
        String cod = "Z" + (SECUENCIA.incrementAndGet() % 1000);
        jdbc.sql("INSERT INTO catalogo_valores (catalogo, codigo, descripcion) VALUES ('tipo_documento', :c, 'Valor de prueba')").param("c", cod).update();
        assertThat(jdbc.sql("DELETE FROM catalogo_valores WHERE catalogo = 'tipo_documento' AND codigo = :c").param("c", cod).update()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- archivos

    @Test
    void laClaveDeUnArchivoEsVisitaActaItOOb() {
        long u = usuario();
        long v = visita("anual", puntoExistente(), u, null, null, null, null);
        for (String buena : new String[]{"visita", "acta", "it:HB-01", "it:P05-07", "ob:OB-12", "ob:OB-A-1"}) {
            archivo(v, u, buena);
        }
        for (String mala : new String[]{"foto", "Visita", "it:", "it:-X", "ob:12", "ob:OB-", "ob:OB-a", "it:hb-01", "visita:1"}) {
            assertThatThrownBy(() -> archivo(v, u, mala)).as("clave '%s'", mala).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ---------------------------------------------------------------- copia de usuarios y mensajes procesados

    @Test
    void usuariosCopiaTieneLasColumnasDelContratoYSuClavePrimariaEsUid() {
        List<String> columnas = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'visitas' "
                + "AND table_name = 'usuarios_copia'").query(String.class).list();
        assertThat(columnas).contains("uid", "usuario", "nombre", "documento", "correo", "activo", "perfiles", "alcance_fuerza",
                "alcance_punto", "alcance_regional", "version");
        assertThat(clavePrimaria("usuarios_copia")).containsExactly("uid");
        assertThat(tipoColumna("usuarios_copia", "uid")).isEqualTo("int8");
        assertThat(tipoColumna("usuarios_copia", "activo")).isEqualTo("bool");
        assertThat(tipoColumna("usuarios_copia", "perfiles")).isIn("_int2", "_int4");
        assertThat(tipoColumna("usuarios_copia", "alcance_fuerza")).isEqualTo("text");
        assertThat(tipoColumna("usuarios_copia", "alcance_punto")).isEqualTo("int8");
        assertThat(tipoColumna("usuarios_copia", "alcance_regional")).isEqualTo("int8");
        assertThat(tipoColumna("usuarios_copia", "version")).isIn("int2", "int4", "int8");
    }

    @Test
    void laAplicacionEscribeLaCopiaDeUsuarios() {
        long uid = usuario();
        assertThat(jdbc.sql("UPDATE usuarios_copia SET activo = false, version = version + 1 WHERE uid = :u").param("u", uid).update()).isEqualTo(1);
    }

    @Test
    void mensajesProcesadosTieneIdUuidComoClavePrimaria() {
        assertThat(tipoColumna("mensajes_procesados", "id")).isEqualTo("uuid");
        assertThat(clavePrimaria("mensajes_procesados")).containsExactly("id");
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO mensajes_procesados (id, tipo) VALUES (:i, 'usuario.actualizado')").param("i", id).update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO mensajes_procesados (id, tipo) VALUES (:i, 'usuario.actualizado')").param("i", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- ayudas

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String tipoColumna(String tabla, String columna) {
        return jdbc.sql("SELECT udt_name FROM information_schema.columns WHERE table_schema = 'visitas' AND table_name = :t AND column_name = :c")
                .param("t", tabla).param("c", columna).query(String.class).single();
    }

    private List<String> clavePrimaria(String tabla) {
        return jdbc.sql("SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey) "
                + "WHERE i.indrelid = CAST(:t AS regclass) AND i.indisprimary").param("t", "visitas." + tabla).query(String.class).list();
    }

    private static String sufijo() {
        return Long.toString(SECUENCIA.incrementAndGet());
    }

    private static String letras(int n) {
        StringBuilder s = new StringBuilder();
        long x = SECUENCIA.incrementAndGet();
        for (int i = 0; i < n; i++) {
            s.append((char) ('A' + (x % 26)));
            x = x / 26 + 7 * (i + 1);
        }
        return s.toString();
    }

    private void fuerza(String codigo) {
        jdbc.sql("INSERT INTO par_fuerzas (codigo, nombre) VALUES (:c, 'Fuerza de prueba')").param("c", codigo).update();
    }

    private void punto(String nombre, String unidad, String modalidad, String sigla) {
        jdbc.sql("INSERT INTO puntos (nombre, unidad, fuerza, modalidad, sigla, creado_por) VALUES (:n, :u, 'EJC', :m, :s, 'prueba')")
                .param("n", nombre).param("u", unidad).param("m", modalidad).param("s", sigla).update();
    }

    /** Un punto de la semilla (solo lectura). */
    private long puntoExistente() {
        return jdbc.sql("SELECT min(id) FROM puntos WHERE creado_por = 'carga inicial'").query(Long.class).single();
    }

    /** Un usuario de la copia local (uid único). */
    private long usuario() {
        long uid = 9_000_000_000L + SECUENCIA.incrementAndGet();
        jdbc.sql("INSERT INTO usuarios_copia (uid, usuario, nombre, documento, correo, activo, perfiles, version) "
                + "VALUES (:u, :us, 'Nombre Ficticio', :d, :c, true, '{3}', 1)")
                .param("u", uid).param("us", "u" + uid).param("d", "D" + uid).param("c", "u" + uid + "@visitas.prueba.local").update();
        return uid;
    }

    private long visita(String tipo, long punto, long usuario, String numero, String fechaSolicitud, Long origen, Integer seguimientoN) {
        return jdbc.sql("INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, fecha_solicitud, origen_id, seguimiento_n, creado_por) "
                + "VALUES (:num, :t, :p, current_date, :u, 'alcance de prueba', CAST(:fs AS date), :o, :s, :u) RETURNING id")
                .param("num", numero).param("t", tipo).param("p", punto).param("u", usuario).param("fs", fechaSolicitud)
                .param("o", origen, Types.BIGINT).param("s", seguimientoN == null ? null : seguimientoN.shortValue(), Types.SMALLINT)
                .query(Long.class).single();
    }

    private void visitaConEstado(long punto, long usuario, String estado) {
        jdbc.sql("INSERT INTO visitas (tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por) "
                + "VALUES ('anual', :p, current_date, :u, 'alcance de prueba', :e, :u)")
                .param("p", punto).param("u", usuario).param("e", estado).update();
    }

    private void equipo(long visita, long usuario, String funcion, int orden) {
        jdbc.sql("INSERT INTO visitas_auditores (visita_id, usuario_id, funcion, orden) VALUES (:v, :u, :f, :o)")
                .param("v", visita).param("u", usuario).param("f", funcion).param("o", (short) orden).update();
    }

    private void archivo(long visita, long usuario, String clave) {
        jdbc.sql("INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, subido_por) "
                + "VALUES (:v, :c, 'a.pdf', :r, 'application/pdf', 10, :h, :u)")
                .param("v", visita).param("c", clave).param("r", "visitas/" + UUID.randomUUID() + ".pdf")
                .param("h", "a".repeat(64)).param("u", usuario).update();
    }
}
