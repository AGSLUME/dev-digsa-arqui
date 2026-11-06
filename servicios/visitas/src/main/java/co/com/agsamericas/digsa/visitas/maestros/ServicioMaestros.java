package co.com.agsamericas.digsa.visitas.maestros;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Reparto;
import co.com.agsamericas.digsa.visitas.api.modelo.Auditor;
import co.com.agsamericas.digsa.visitas.api.modelo.Obligacion;
import co.com.agsamericas.digsa.visitas.api.modelo.Opl;
import co.com.agsamericas.digsa.visitas.api.modelo.Punto;
import co.com.agsamericas.digsa.visitas.compartido.BitacoraVisitas;
import co.com.agsamericas.digsa.visitas.usuarios.DirectorioUsuarios;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maestros del coordinador: puntos (ESM del anexo del OPL, farmacias alternas y nivel central), datos propios de los auditores (rol técnico
 * y municipio base), el operador logístico y las obligaciones del contrato. Portado de {@code maestros.ServicioMaestros} del monolito
 * (develop @ 1ff82de) con los mismos textos. Diferencias: los usuarios salen de la copia local ({@link DirectorioUsuarios}); «ya se calificó
 * en visitas» se consulta por {@link UsoObligaciones} (sobre la tabla de ejecución, VISITAS B).
 */
@Service
public class ServicioMaestros {

    static final Set<String> UNIDADES = Set.of("ESM", "FA", "NC");
    /** Datos de contacto del punto (Ley 1581): la bitácora dice que cambiaron, sin los valores. */
    private static final Set<String> CONTACTO = Set.of("contacto", "correos");

    public record DatosPunto(String sigla, String nombre, String unidad, String fuerza, String modalidad, String nit, String municipio,
                             String direccion, String nivel, String horario, String contacto, List<String> correos, Long regionalId,
                             String llegaDesde, Long auditorAsignado, Boolean exigeQf) {
    }

    public record DatosOpl(String nombre, String nit, List<String> correos) {
    }

    public record DatosObligacion(String prestador, String codigo, String texto, String fuente, Boolean activo) {
    }

    private static final String COLUMNAS_PUNTO = """
            p.id, p.sigla, p.nombre, p.unidad, p.fuerza, p.modalidad, p.nit, p.municipio, m.nombre AS municipio_nombre,
            d.nombre AS departamento, p.direccion, p.nivel, p.horario, p.contacto, p.correos, p.regional_id, r.nombre AS regional,
            p.regional_opl, p.llega_desde, p.auditor_asignado, p.exige_qf, p.activo, p.fuente""";
    private static final String DESDE_PUNTOS = """
             FROM puntos p
             LEFT JOIN par_municipios m ON m.codigo = p.municipio
             LEFT JOIN par_departamentos d ON d.codigo = m.departamento
             LEFT JOIN par_regionales r ON r.id = p.regional_id""";

    private final JdbcClient jdbc;
    private final BitacoraVisitas bitacora;
    private final DirectorioUsuarios usuarios;
    private final UsoObligaciones usoObligaciones;
    private final Clock reloj;

    public ServicioMaestros(JdbcClient jdbc, BitacoraVisitas bitacora, DirectorioUsuarios usuarios,
                            UsoObligaciones usoObligaciones, Clock reloj) {
        this.jdbc = jdbc;
        this.bitacora = bitacora;
        this.usuarios = usuarios;
        this.usoObligaciones = usoObligaciones;
        this.reloj = reloj;
    }

    // ---------- Puntos ----------

    @Transactional(readOnly = true)
    public List<Punto> puntos(String unidad, String fuerza, String texto, Boolean activo) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNAS_PUNTO + " " + DESDE_PUNTOS + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (unidad != null && !unidad.isBlank()) {
            sql.append(" AND p.unidad = :unidad");
            params.put("unidad", unidad);
        }
        if (fuerza != null && !fuerza.isBlank()) {
            sql.append(" AND p.fuerza = :fuerza");
            params.put("fuerza", fuerza);
        }
        if (activo != null) {
            sql.append(" AND p.activo = :activo");
            params.put("activo", activo);
        }
        if (texto != null && !texto.isBlank()) {
            sql.append(" AND (p.nombre ILIKE :t OR p.sigla ILIKE :t OR m.nombre ILIKE :t)");
            params.put("t", "%" + texto.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY p.unidad, p.fuerza, p.nombre LIMIT 2000");
        JdbcClient.StatementSpec q = jdbc.sql(sql.toString());
        for (var e : params.entrySet()) {
            q = q.param(e.getKey(), e.getValue());
        }
        return q.query(ServicioMaestros::filaPunto).list();
    }

    static Punto filaPunto(ResultSet rs, int n) throws SQLException {
        return new Punto(rs.getLong("id"), rs.getString("nombre"), rs.getString("unidad"), rs.getString("fuerza"), arreglo(rs.getArray("correos")),
                rs.getBoolean("exige_qf"), rs.getBoolean("activo"))
                .sigla(rs.getString("sigla"))
                .modalidad(rs.getString("modalidad"))
                .nit(rs.getString("nit"))
                .municipio(rs.getString("municipio"))
                .municipioNombre(rs.getString("municipio_nombre"))
                .departamento(rs.getString("departamento"))
                .direccion(rs.getString("direccion"))
                .nivel(rs.getString("nivel"))
                .horario(rs.getString("horario"))
                .contacto(rs.getString("contacto"))
                .regionalId((Long) rs.getObject("regional_id", Long.class))
                .regional(rs.getString("regional"))
                .regionalOpl(rs.getString("regional_opl"))
                .llegaDesde(rs.getString("llega_desde"))
                .auditorAsignado((Long) rs.getObject("auditor_asignado", Long.class))
                .fuente(rs.getString("fuente"));
    }

    private static List<String> arreglo(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    @Transactional
    public long crearPunto(DatosPunto p) {
        DatosPunto v = validar(p);
        try {
            long id = jdbc.sql("""
                    INSERT INTO puntos (sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, nivel, horario, contacto, correos,
                      regional_id, llega_desde, auditor_asignado, exige_qf, creado_por, fuente)
                    VALUES (:sigla, :nombre, :unidad, :fuerza, :modalidad, :nit, :municipio, :direccion, :nivel, :horario, :contacto, :correos,
                      :regional, :llega, :auditor, :qf, :quien, 'Creado en la aplicación')
                    RETURNING id""")
                    .params(parametros(v)).param("quien", quien()).query(Long.class).single();
            bitacora.registrar("punto_creado", "Punto " + id + " creado: «" + Bitacora.cortar(v.nombre(), 80) + "» (" + v.unidad() + ", " + v.fuerza() + ")");
            return id;
        } catch (DuplicateKeyException e) {
            throw duplicado();
        } catch (DataIntegrityViolationException e) {
            // La fuerza, el municipio o la regional se borró o cambió entre la validación y el guardado (revisión Java M6): 422, no 500
            throw ErrorNegocio.validacion("La fuerza, el municipio o la regional cambió mientras guardaba. Revise e intente de nuevo.");
        }
    }

    @Transactional
    public void editarPunto(long id, DatosPunto p) {
        Punto antes = jdbc.sql("SELECT " + COLUMNAS_PUNTO + " " + DESDE_PUNTOS + " WHERE p.id = :id FOR UPDATE OF p")
                .param("id", id).query(ServicioMaestros::filaPunto).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("El punto no existe."));
        DatosPunto v = validar(p);
        try {
            jdbc.sql("""
                    UPDATE puntos SET sigla = :sigla, nombre = :nombre, unidad = :unidad, fuerza = :fuerza, modalidad = :modalidad, nit = :nit,
                      municipio = :municipio, direccion = :direccion, nivel = :nivel, horario = :horario, contacto = :contacto, correos = :correos,
                      regional_id = :regional, llega_desde = :llega, auditor_asignado = :auditor, exige_qf = :qf, actualizado = :ahora
                    WHERE id = :id""")
                    .params(parametros(v)).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw duplicado();
        } catch (DataIntegrityViolationException e) {
            // La fuerza, el municipio o la regional se borró o cambió entre la validación y el guardado (revisión Java M6): 422, no 500
            throw ErrorNegocio.validacion("La fuerza, el municipio o la regional cambió mientras guardaba. Revise e intente de nuevo.");
        }
        String cambios = Bitacora.cambios(fotoPunto(antes), fotoPunto(v), CONTACTO);
        bitacora.registrar("punto_editado", "Punto " + id + (cambios.isEmpty() ? " sin cambios" : ": " + cambios));
    }

    @Transactional
    public void estadoPunto(long id, boolean activo) {
        String nombre = jdbc.sql("SELECT nombre FROM puntos WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El punto no existe."));
        if (!activo) {
            long activos = usuarios.usuariosActivosConAlcancePunto(id);
            if (activos > 0) {
                throw ErrorNegocio.validacion("El punto es el alcance de " + activos + " usuarios ESM activos: cámbielos antes de inactivarlo.");
            }
        }
        jdbc.sql("UPDATE puntos SET activo = :a, actualizado = :ahora WHERE id = :id").param("a", activo).param("ahora", OffsetDateTime.now(reloj))
                .param("id", id).update();
        bitacora.registrar("punto_editado", "Punto " + id + " «" + Bitacora.cortar(nombre, 80) + "» " + (activo ? "activado" : "inactivado"));
    }

    private static ErrorNegocio duplicado() {
        return ErrorNegocio.validacion("Ya existe un punto con ese nombre en esa unidad, o con esa sigla.");
    }

    private DatosPunto validar(DatosPunto p) {
        List<String> errores = new ArrayList<>();
        String nombre = texto(p.nombre());
        if (nombre.length() < 3 || nombre.length() > 200) {
            errores.add("el nombre debe tener de 3 a 200 caracteres");
        }
        String unidad = texto(p.unidad()).toUpperCase();
        if (!UNIDADES.contains(unidad)) {
            errores.add("la unidad debe ser ESM, FA o NC");
        }
        String fuerza = texto(p.fuerza()).toUpperCase();
        if (fuerza.isEmpty() && "NC".equals(unidad)) {
            fuerza = "NA";
        }
        if (!existe("SELECT count(*) FROM par_fuerzas WHERE codigo = :v AND activo", fuerza)) {
            errores.add("la fuerza no existe o está inactiva");
        }
        String modalidad = "ESM".equals(unidad) ? vacioANulo(p.modalidad()) : null;
        if ("ESM".equals(unidad) && (modalidad == null || !existe("SELECT count(*) FROM par_modalidades WHERE nombre = :v", modalidad))) {
            errores.add("falta la modalidad del ESM (Dispensación, Suministro, Consignación o Satélite)");
        }
        String nit = "FA".equals(unidad) ? vacioANulo(p.nit()) : null; // el NIT solo aplica a la farmacia alterna
        String municipio = vacioANulo(p.municipio());
        if (municipio != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", municipio)) {
            errores.add("el municipio no existe");
        }
        String llega = vacioANulo(p.llegaDesde());
        if (llega != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", llega)) {
            errores.add("el municipio «se llega desde» no existe");
        }
        if (p.regionalId() != null && !existe("SELECT count(*) FROM par_regionales WHERE id = :v AND activo", p.regionalId())) {
            errores.add("la regional no existe o está inactiva");
        }
        if (p.auditorAsignado() != null && !usuarios.auditorActivo(p.auditorAsignado())) {
            errores.add("el auditor asignado no es un auditor activo");
        }
        String sigla = vacioANulo(p.sigla());
        largo(errores, sigla, 30, "la sigla");
        largo(errores, nit, 20, "el NIT");
        String direccion = vacioANulo(p.direccion());
        largo(errores, direccion, 200, "la dirección");
        String contacto = vacioANulo(p.contacto());
        largo(errores, contacto, 150, "el contacto");
        String nivel = vacioANulo(p.nivel());
        largo(errores, nivel, 40, "el nivel");
        String horario = vacioANulo(p.horario());
        largo(errores, horario, 200, "el horario");
        List<String> correos;
        try {
            correos = correos(p.correos());
        } catch (ErrorNegocio e) {
            errores.add(e.getMessage());
            correos = List.of();
        }
        if (!errores.isEmpty()) {
            throw ErrorNegocio.validacion("Revise el punto: " + String.join("; ", errores) + ".");
        }
        return new DatosPunto(sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, nivel, horario, contacto, correos,
                p.regionalId(), llega, p.auditorAsignado(), Boolean.TRUE.equals(p.exigeQf()));
    }

    private static void largo(List<String> errores, String v, int max, String campo) {
        if (v != null && v.length() > max) {
            errores.add(campo + " tiene máximo " + max + " caracteres");
        }
    }

    private static Map<String, Object> parametros(DatosPunto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigla", v.sigla());
        m.put("nombre", v.nombre());
        m.put("unidad", v.unidad());
        m.put("fuerza", v.fuerza());
        m.put("modalidad", v.modalidad());
        m.put("nit", v.nit());
        m.put("municipio", v.municipio());
        m.put("direccion", v.direccion());
        m.put("nivel", v.nivel());
        m.put("horario", v.horario());
        m.put("contacto", v.contacto());
        m.put("correos", v.correos().toArray(String[]::new));
        m.put("regional", v.regionalId());
        m.put("llega", v.llegaDesde());
        m.put("auditor", v.auditorAsignado());
        m.put("qf", v.exigeQf());
        return m;
    }

    private static Map<String, Object> fotoPunto(Punto f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigla", f.getSigla());
        m.put("nombre", f.getNombre());
        m.put("unidad", f.getUnidad());
        m.put("fuerza", f.getFuerza());
        m.put("modalidad", f.getModalidad());
        m.put("nit", f.getNit());
        m.put("municipio", f.getMunicipio());
        m.put("direccion", f.getDireccion());
        m.put("nivel", f.getNivel());
        m.put("horario", f.getHorario());
        m.put("contacto", f.getContacto());
        m.put("correos", f.getCorreos());
        m.put("regionalId", f.getRegionalId());
        m.put("llegaDesde", f.getLlegaDesde());
        m.put("auditorAsignado", f.getAuditorAsignado());
        m.put("exigeQf", f.getExigeQf());
        return m;
    }

    private static Map<String, Object> fotoPunto(DatosPunto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigla", v.sigla());
        m.put("nombre", v.nombre());
        m.put("unidad", v.unidad());
        m.put("fuerza", v.fuerza());
        m.put("modalidad", v.modalidad());
        m.put("nit", v.nit());
        m.put("municipio", v.municipio());
        m.put("direccion", v.direccion());
        m.put("nivel", v.nivel());
        m.put("horario", v.horario());
        m.put("contacto", v.contacto());
        m.put("correos", v.correos());
        m.put("regionalId", v.regionalId());
        m.put("llegaDesde", v.llegaDesde());
        m.put("auditorAsignado", v.auditorAsignado());
        m.put("exigeQf", v.exigeQf());
        return m;
    }

    // ---------- Auditores ----------

    /** Usuarios con el perfil Auditor (de la copia local), con su rol técnico y municipio base. */
    @Transactional(readOnly = true)
    public List<Auditor> auditores() {
        Map<Long, Map<String, Object>> propios = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT a.usuario_id, a.rol, a.municipio_base, m.nombre AS municipio_nombre
                FROM auditores a LEFT JOIN par_municipios m ON m.codigo = a.municipio_base""").query(rs -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("rol", rs.getString("rol"));
            x.put("municipioBase", rs.getString("municipio_base"));
            x.put("municipioNombre", rs.getString("municipio_nombre"));
            propios.put(rs.getLong("usuario_id"), x);
        });
        List<Auditor> r = new ArrayList<>();
        for (DirectorioUsuarios.UsuarioCopia u : usuarios.auditores()) {
            Map<String, Object> a = propios.getOrDefault(u.uid(), Map.of());
            String rol = a.get("rol") == null ? "otro" : (String) a.get("rol");
            r.add(new Auditor(u.uid(), u.nombre(), u.usuario(), u.activo(), rol, Reparto.ROLES.getOrDefault(rol, "Otro"))
                    .documento(u.documento())
                    .municipioBase((String) a.get("municipioBase"))
                    .municipioNombre((String) a.get("municipioNombre")));
        }
        return r;
    }

    /** Rol técnico (decisión 2a) y municipio base. Cambiar el rol no recalcula el reparto de visitas ya programadas. */
    @Transactional
    public void editarAuditor(long usuarioId, String rolEntrada, String municipioBase) {
        if (!usuarios.esAuditor(usuarioId)) {
            throw ErrorNegocio.noEncontrado("El usuario no es auditor.");
        }
        String rol = texto(rolEntrada);
        if (!Reparto.ROLES.containsKey(rol)) {
            throw ErrorNegocio.validacion("Elija el rol: " + String.join(", ", Reparto.ROLES.values()) + ".");
        }
        String municipio = vacioANulo(municipioBase);
        if (municipio != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", municipio)) {
            throw ErrorNegocio.validacion("El municipio base no existe.");
        }
        Map<String, Object> antes = jdbc.sql("SELECT rol, municipio_base FROM auditores WHERE usuario_id = :id FOR UPDATE").param("id", usuarioId)
                .query().listOfRows().stream().findFirst().orElse(Map.of("rol", "otro"));
        jdbc.sql("""
                INSERT INTO auditores (usuario_id, rol, municipio_base, actualizado) VALUES (:id, :rol, :m, :ahora)
                ON CONFLICT (usuario_id) DO UPDATE SET rol = EXCLUDED.rol, municipio_base = EXCLUDED.municipio_base, actualizado = EXCLUDED.actualizado""")
                .param("id", usuarioId).param("rol", rol).param("m", municipio).param("ahora", OffsetDateTime.now(reloj)).update();
        Map<String, Object> despues = new LinkedHashMap<>();
        despues.put("rol", rol);
        despues.put("municipio_base", municipio);
        String cambios = Bitacora.cambios(antes, despues, Set.of());
        bitacora.registrar("auditor_editado", "Auditor " + usuarioId + (cambios.isEmpty() ? " sin cambios" : ": " + cambios));
    }

    // ---------- Operador logístico ----------

    @Transactional(readOnly = true)
    public Opl opl() {
        return jdbc.sql("SELECT id, nombre, nit, correos FROM opl WHERE activo ORDER BY id LIMIT 1")
                .query((rs, n) -> new Opl(rs.getString("nombre"), rs.getString("nit"), arreglo(rs.getArray("correos"))).id(rs.getLong("id")))
                .optional().orElseGet(() -> new Opl("", "", List.of()).id(null));
    }

    /** Un solo operador logístico activo (el contrato vigente); sus correos reciben la notificación previa y el acta. */
    @Transactional
    public void guardarOpl(DatosOpl o) {
        String nombre = texto(o.nombre());
        if (nombre.length() < 3 || nombre.length() > 200) {
            throw ErrorNegocio.validacion("La razón social del operador debe tener de 3 a 200 caracteres.");
        }
        String nit = vacioANulo(o.nit());
        if (nit != null && !nit.matches("^[0-9]{6,12}(-[0-9])?$")) {
            throw ErrorNegocio.validacion("El NIT debe tener de 6 a 12 dígitos y, si acaso, el dígito de verificación (900123456-7).");
        }
        List<String> correos = correos(o.correos());
        if (correos.isEmpty()) {
            throw ErrorNegocio.validacion("Registre al menos un correo del operador: recibe la notificación previa (Anexo 3 p.9).");
        }
        // Dos guardados a la vez sin OPL previo: el segundo espera al primero (el índice único de un solo activo lo garantiza igual)
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('visitas.opl'))").query().listOfRows();
        Long id = jdbc.sql("SELECT id FROM opl WHERE activo ORDER BY id LIMIT 1 FOR UPDATE").query(Long.class).optional().orElse(null);
        if (id == null) {
            jdbc.sql("INSERT INTO opl (nombre, nit, correos) VALUES (:n, :nit, :c)").param("n", nombre).param("nit", nit)
                    .param("c", correos.toArray(String[]::new)).update();
        } else {
            jdbc.sql("UPDATE opl SET nombre = :n, nit = :nit, correos = :c, actualizado = :ahora WHERE id = :id").param("n", nombre).param("nit", nit)
                    .param("c", correos.toArray(String[]::new)).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        }
        bitacora.registrar("catalogo_editado", "Operador logístico actualizado: «" + Bitacora.cortar(nombre, 80) + "»");
    }

    // ---------- Obligaciones del contrato (parte OBL del formulario) ----------

    public List<Obligacion> obligaciones() {
        return jdbc.sql("SELECT id, prestador, codigo, texto, fuente, activo FROM obligaciones_contrato ORDER BY prestador, orden, codigo")
                .query((rs, n) -> new Obligacion(rs.getLong("id"), Obligacion.PrestadorEnum.fromValue(rs.getString("prestador")),
                        rs.getString("codigo"), rs.getString("texto"), rs.getBoolean("activo")).fuente(rs.getString("fuente")))
                .list();
    }

    /** El coordinador registra las obligaciones de la minuta del OPL y de la farmacia alterna (mientras llega la lectura de la minuta). */
    @Transactional
    public long guardarObligacion(Long id, DatosObligacion o) {
        String prestador = texto(o.prestador()).toLowerCase();
        if (!Set.of("opl", "fa").contains(prestador)) {
            throw ErrorNegocio.validacion("El prestador debe ser el OPL o la farmacia alterna.");
        }
        String codigo = texto(o.codigo()).toUpperCase();
        if (!codigo.matches("^OB-[A-Z0-9-]{1,20}$")) {
            throw ErrorNegocio.validacion("El código de la obligación empieza por OB- (p. ej. OB-OPL-01).");
        }
        String t = texto(o.texto());
        if (t.length() < 10 || t.length() > 2000) {
            throw ErrorNegocio.validacion("El texto de la obligación debe tener de 10 a 2000 caracteres.");
        }
        String fuente = vacioANulo(o.fuente());
        if (fuente != null && fuente.length() > 200) {
            // El monolito dejaba que fallara el CHECK de la base (500); aquí es un 422 con texto
            throw ErrorNegocio.validacion("La fuente de la obligación tiene máximo 200 caracteres.");
        }
        boolean activo = o.activo() == null || o.activo();
        try {
            if (id == null) {
                long nuevo = jdbc.sql("""
                        INSERT INTO obligaciones_contrato (prestador, codigo, texto, fuente, activo, orden)
                        VALUES (:p, :c, :t, :f, :a, (SELECT coalesce(max(orden), 0) + 1 FROM obligaciones_contrato)) RETURNING id""")
                        .param("p", prestador).param("c", codigo).param("t", t).param("f", fuente).param("a", activo).query(Long.class).single();
                bitacora.registrar("catalogo_creado", "Obligación " + codigo + " creada");
                return nuevo;
            }
            // Una obligación ya calificada conserva su código y su prestador: la clave «ob:<código>» une el 5W2H, las respuestas y los soportes del OPL
            Map<String, Object> actual = jdbc.sql("SELECT codigo, prestador FROM obligaciones_contrato WHERE id = :id FOR UPDATE").param("id", id)
                    .query().listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("La obligación no existe."));
            boolean cambiaClave = !codigo.equals(actual.get("codigo")) || !prestador.equals(actual.get("prestador"));
            if (cambiaClave && usoObligaciones.calificada(id)) {
                throw ErrorNegocio.validacion("La obligación ya se calificó en visitas: no cambie su código ni su prestador; inactívela y cree otra.");
            }
            jdbc.sql("UPDATE obligaciones_contrato SET prestador = :p, codigo = :c, texto = :t, fuente = :f, activo = :a WHERE id = :id")
                    .param("p", prestador).param("c", codigo).param("t", t).param("f", fuente).param("a", activo).param("id", id).update();
            bitacora.registrar("catalogo_editado", "Obligación " + codigo + " editada");
            return id;
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe la obligación " + codigo + ".");
        }
    }

    // ---------- Apoyo ----------

    private boolean existe(String sql, Object valor) {
        return jdbc.sql(sql).param("v", valor).query(Long.class).single() > 0;
    }

    private static String quien() {
        return UsuarioActual.actual().map(UsuarioActual::usuario).orElse(null);
    }

    static List<String> correos(List<String> entrada) {
        Set<String> r = new LinkedHashSet<>();
        if (entrada != null) {
            for (String c : entrada) {
                for (String parte : texto(c).split("[,;\\s]+")) {
                    String n = Correos.normalizar(parte);
                    if (n.isEmpty()) {
                        continue;
                    }
                    if (!Correos.valido(n)) {
                        throw ErrorNegocio.validacion("el correo «" + Bitacora.cortar(parte, 60) + "» no es válido");
                    }
                    r.add(n);
                }
            }
        }
        if (r.size() > 10) {
            throw ErrorNegocio.validacion("máximo 10 correos");
        }
        return List.copyOf(r);
    }

    private static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    private static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
