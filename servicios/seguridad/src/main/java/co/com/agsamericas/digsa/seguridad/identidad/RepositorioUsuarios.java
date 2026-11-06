package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a usuarios y perfiles (portado de {@code identidad.RepositorioUsuarios} del monolito). Las operaciones críticas son UPDATE
 * condicionales atómicos, no bloqueos de aplicación. La sesión única está en {@link RepositorioSesiones}.
 */
@Repository
public class RepositorioUsuarios {

    /** Horas que vale la contraseña inicial sin usarse: la conoce el administrador y suele quedar en papel o en un chat. */
    public static final int HORAS_CLAVE_INICIAL = 72;

    private static final Logger LOG = LoggerFactory.getLogger(RepositorioUsuarios.class);

    private static final String COLUMNAS = """
            u.id, u.nombre, u.documento, u.telefono, u.usuario, u.correo, u.clave_hash, u.activo, u.clave_definida, u.clave_vence,
            u.debe_cambiar_clave, u.clave_actualizada, u.token_hash, u.token_vence, u.intentos, u.bloqueado_hasta, u.ultimo_ingreso,
            u.alcance_fuerza, u.alcance_punto, u.alcance_regional, u.clave_inicial,
            COALESCE((SELECT string_agg(up.perfil_id::text, ',') FROM usuarios_perfiles up JOIN perfiles p ON p.id = up.perfil_id
                      WHERE up.usuario_id = u.id AND p.activo AND p.base_id IS NULL), '') AS perfiles,
            COALESCE((SELECT string_agg(p.id || ':' || p.base_id || ':' || COALESCE(pp.pantalla, ''), ',')
                      FROM usuarios_perfiles up JOIN perfiles p ON p.id = up.perfil_id JOIN perfiles b ON b.id = p.base_id AND b.activo
                      LEFT JOIN perfiles_pantallas pp ON pp.perfil_id = p.id
                      WHERE up.usuario_id = u.id AND p.activo), '') AS roles_adicionales""";

    private final JdbcClient jdbc;

    public RepositorioUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Usuario> porId(long id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.id = :id").param("id", id).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porUsuario(String usuario) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.usuario = :u").param("u", usuario).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porUsuarioOCorreo(String valor) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.usuario = :v OR u.correo = :v ORDER BY u.id LIMIT 1")
                .param("v", valor).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porTokenHash(String hash) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.token_hash = :h").param("h", hash).query(RepositorioUsuarios::fila).optional();
    }

    /**
     * Suma un intento fallido de forma atómica: reinicia el contador si el bloqueo ya venció y bloquea al llegar al máximo.
     *
     * @return true si este intento bloqueó la cuenta
     */
    public boolean registrarFallo(long id, Instant ahora, int maximo, int minutosBloqueo) {
        return jdbc.sql("""
                UPDATE usuarios SET
                  intentos = CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 1 ELSE intentos + 1 END,
                  bloqueado_hasta = CASE
                    WHEN (CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 1 ELSE intentos + 1 END) >= :maximo
                      THEN :hasta
                    WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN NULL
                    ELSE bloqueado_hasta END,
                  actualizado = :ahora
                WHERE id = :id AND (bloqueado_hasta IS NULL OR bloqueado_hasta <= :ahora)
                RETURNING bloqueado_hasta IS NOT NULL AND bloqueado_hasta > :ahora""")
                .param("ahora", odt(ahora)).param("maximo", maximo).param("hasta", odt(ahora.plusSeconds(minutosBloqueo * 60L)))
                .param("id", id).query(Boolean.class).optional().orElse(false);
    }

    /**
     * Reserva un intento de contraseña ANTES de compararla: suma uno solo si la cuenta no está bloqueada y no hay ya {@code maximo}
     * intentos (un bloqueo vencido reinicia la cuenta). Atómico: N ingresos en paralelo no comparan más de {@code maximo} veces.
     *
     * @return el número de este intento, o vacío si la cuenta está bloqueada o ya agotó los intentos
     */
    public Optional<Integer> reservarIntento(long id, Instant ahora, int maximo) {
        return jdbc.sql("""
                UPDATE usuarios SET
                  intentos = CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 1 ELSE intentos + 1 END,
                  bloqueado_hasta = CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN NULL ELSE bloqueado_hasta END
                WHERE id = :id AND (bloqueado_hasta IS NULL OR bloqueado_hasta <= :ahora)
                  AND (CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 0 ELSE intentos END) < :maximo
                RETURNING intentos""")
                .param("ahora", odt(ahora)).param("id", id).param("maximo", maximo).query(Integer.class).optional();
    }

    /** Tras una contraseña errada: si este intento llegó al máximo, bloquea {@code minutosBloqueo}. @return true si bloqueó */
    public boolean bloquearSiAlcanzo(long id, int intento, Instant ahora, int maximo, int minutosBloqueo) {
        if (intento < maximo) {
            return false;
        }
        return jdbc.sql("UPDATE usuarios SET bloqueado_hasta = :hasta, actualizado = :ahora WHERE id = :id AND bloqueado_hasta IS NULL")
                .param("hasta", odt(ahora.plusSeconds(minutosBloqueo * 60L))).param("ahora", odt(ahora)).param("id", id).update() == 1;
    }

    public void reiniciarIntentos(long id, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora WHERE id = :id AND (intentos <> 0 OR bloqueado_hasta IS NOT NULL)")
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    public void registrarIngreso(long id, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET ultimo_ingreso = :ahora, intentos = 0, bloqueado_hasta = NULL WHERE id = :id")
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    /**
     * Cambio de la contraseña dentro de la aplicación, sin enlace: la inicial, la vencida o la que el usuario cambia por su cuenta. Solo
     * si la contraseña guardada sigue siendo la que se verificó (un doble envío o un cambio de otro lado no se pisan). Consume el enlace
     * pendiente, si lo hubiera. @return false si la contraseña ya había cambiado
     */
    public boolean cambiarClave(long id, String hashAnterior, String claveHash, Instant vence, Instant ahora) {
        return jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_definida = true, clave_inicial = false, debe_cambiar_clave = false, clave_vence = :vence,
                  clave_actualizada = :ahora, token_hash = NULL, token_vence = NULL, intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora
                WHERE id = :id AND clave_hash = :anterior""")
                .param("c", claveHash).param("vence", odt(vence)).param("ahora", odt(ahora)).param("id", id).param("anterior", hashAnterior)
                .update() == 1;
    }

    /** Guarda el enlace nuevo (reemplaza al anterior). {@code exigirCambio} marca el cambio obligatorio (restablecer del administrador). */
    public void guardarToken(long id, String hash, Instant vence, boolean exigirCambio, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET token_hash = :h, token_vence = :v, actualizado = :ahora,
                  debe_cambiar_clave = CASE WHEN :exigir THEN true ELSE debe_cambiar_clave END,
                  clave_inicial = CASE WHEN :exigir THEN false ELSE clave_inicial END,
                  intentos = CASE WHEN :exigir THEN 0 ELSE intentos END,
                  bloqueado_hasta = CASE WHEN :exigir THEN NULL ELSE bloqueado_hasta END
                WHERE id = :id""")
                .param("h", hash).param("v", odt(vence)).param("exigir", exigirCambio).param("ahora", odt(ahora)).param("id", id).update();
    }

    /** Guarda la contraseña nueva y consume el enlace, solo si el token sigue siendo el mismo (un solo uso). */
    public boolean definirClave(long id, String tokenHash, String claveHash, Instant vence, Instant ahora) {
        return jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_definida = true, debe_cambiar_clave = false, clave_vence = :vence,
                  clave_actualizada = :ahora, token_hash = NULL, token_vence = NULL, intentos = 0, bloqueado_hasta = NULL,
                  clave_inicial = false, actualizado = :ahora
                WHERE id = :id AND token_hash = :t""")
                .param("c", claveHash).param("vence", odt(vence)).param("ahora", odt(ahora)).param("id", id).param("t", tokenHash)
                .update() == 1;
    }

    /** El enlace pendiente deja de servir. */
    public void anularEnlace(long id) {
        jdbc.sql("UPDATE usuarios SET token_hash = NULL, token_vence = NULL WHERE id = :id").param("id", id).update();
    }

    // ---------- Administración (S2; portado de RepositorioUsuarios del monolito) ----------

    public List<Usuario> todos() {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u ORDER BY u.nombre, u.id").query(RepositorioUsuarios::fila).list();
    }

    public boolean existeUsuario(String usuario, long excepto) {
        return jdbc.sql("SELECT count(*) FROM usuarios WHERE usuario = :u AND id <> :id").param("u", usuario).param("id", excepto)
                .query(Long.class).single() > 0;
    }

    public boolean existeCorreo(String correo, long excepto) {
        return jdbc.sql("SELECT count(*) FROM usuarios WHERE correo = :c AND id <> :id").param("c", correo).param("id", excepto)
                .query(Long.class).single() > 0;
    }

    public long crear(String nombre, String documento, String telefono, String usuario, String correo, String claveHash, String fuerza,
                      Long punto, Long regional, Instant ahora) {
        return jdbc.sql("""
                INSERT INTO usuarios (nombre, documento, telefono, usuario, correo, clave_hash, alcance_fuerza, alcance_punto,
                                      alcance_regional, creado, actualizado)
                VALUES (:nombre, :documento, :telefono, :usuario, :correo, :clave, :fuerza, :punto, :regional, :ahora, :ahora)
                RETURNING id""")
                .param("nombre", nombre).param("documento", documento).param("telefono", telefono).param("usuario", usuario)
                .param("correo", correo).param("clave", claveHash).param("fuerza", fuerza).param("punto", punto)
                .param("regional", regional).param("ahora", odt(ahora)).query(Long.class).single();
    }

    public void actualizar(long id, String nombre, String documento, String telefono, String usuario, String correo, String fuerza,
                           Long punto, Long regional, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET nombre = :nombre, documento = :documento, telefono = :telefono, usuario = :usuario, correo = :correo,
                  alcance_fuerza = :fuerza, alcance_punto = :punto, alcance_regional = :regional, actualizado = :ahora
                WHERE id = :id""")
                .param("nombre", nombre).param("documento", documento).param("telefono", telefono).param("usuario", usuario)
                .param("correo", correo).param("fuerza", fuerza).param("punto", punto).param("regional", regional)
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    /**
     * Asignaciones del usuario (perfiles base y roles adicionales, por id): reemplaza las activas. Las de roles inactivos se conservan: si el
     * rol se reactiva, el usuario lo vuelve a tener.
     */
    public void reemplazarAsignados(long id, java.util.Collection<Integer> perfilIds) {
        jdbc.sql("DELETE FROM usuarios_perfiles WHERE usuario_id = :id AND perfil_id IN (SELECT id FROM perfiles WHERE activo)")
                .param("id", id).update();
        for (Integer p : perfilIds) {
            jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p) ON CONFLICT DO NOTHING").param("u", id)
                    .param("p", p).update();
        }
    }

    /** Bloquea la fila del usuario hasta el fin de la transacción (ediciones simultáneas). */
    public void bloquear(long id) {
        jdbc.sql("SELECT id FROM usuarios WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
    }

    public void cambiarEstado(long id, boolean activo, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET activo = :a, actualizado = :ahora WHERE id = :id").param("a", activo).param("ahora", odt(ahora))
                .param("id", id).update();
    }

    /** Quita el bloqueo vigente; si no estaba bloqueado no toca los intentos. @return si lo desbloqueó */
    public boolean desbloquear(long id, Instant ahora) {
        return jdbc.sql("UPDATE usuarios SET intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora WHERE id = :id AND bloqueado_hasta > :ahora")
                .param("ahora", odt(ahora)).param("id", id).update() == 1;
    }

    /** Contraseña inicial asignada por el administrador: vale 72 horas para ingresar y obliga a cambiarla en la aplicación. */
    public void asignarClaveInicial(long id, String claveHash, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_definida = true, clave_inicial = true, debe_cambiar_clave = true, clave_vence = :vence,
                  clave_actualizada = :ahora, token_hash = NULL, token_vence = NULL, intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora
                WHERE id = :id""").param("c", claveHash).param("vence", odt(ahora.plusSeconds(3600L * HORAS_CLAVE_INICIAL)))
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    /** Bloquea, en orden de id, a los usuarios activos con el perfil Administrador (guarda del último administrador). */
    public void bloquearAdministradoresActivos() {
        jdbc.sql("""
                SELECT u.id FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id
                WHERE up.perfil_id = :adm AND u.activo ORDER BY u.id FOR UPDATE OF u""")
                .param("adm", co.com.agsamericas.digsa.reglas.Perfil.ADMINISTRADOR.id()).query(Long.class).list();
    }

    /** Usuarios activos con el perfil Administrador, sin contar a {@code excepto}. */
    public long contarAdministradoresActivos(long excepto) {
        return jdbc.sql("""
                SELECT count(*) FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id
                WHERE up.perfil_id = :adm AND u.activo AND u.id <> :excepto""")
                .param("adm", co.com.agsamericas.digsa.reglas.Perfil.ADMINISTRADOR.id()).param("excepto", excepto).query(Long.class).single();
    }

    /** El nombre del perfil, con su fila bloqueada hasta el fin de la transacción. */
    public Optional<String> nombrePerfilBloqueado(int id) {
        return jdbc.sql("SELECT nombre FROM perfiles WHERE id = :id FOR NO KEY UPDATE").param("id", id).query(String.class).optional();
    }

    public void renombrarPerfil(int id, String nombre, String descripcion) {
        jdbc.sql("UPDATE perfiles SET nombre = :n, descripcion = :d WHERE id = :id").param("n", nombre).param("d", descripcion).param("id", id)
                .update();
    }

    /** Perfiles y roles adicionales activos que se pueden asignar (Consulta queda fuera); los roles traen su perfil base. */
    public List<Map<String, Object>> perfilesActivos() {
        return jdbc.sql("SELECT id, nombre, descripcion, base_id AS \"baseId\" FROM perfiles WHERE activo ORDER BY base_id NULLS FIRST, id")
                .query().listOfRows();
    }

    /** Perfiles y roles activos, con la base de cada rol adicional (nula en los perfiles base). */
    public Map<Integer, Integer> activosConBase() {
        Map<Integer, Integer> m = new HashMap<>();
        jdbc.sql("SELECT id, base_id FROM perfiles WHERE activo").query((rs, n) -> m.put(rs.getInt(1), (Integer) rs.getObject(2))).list();
        return m;
    }

    /** Roles adicionales activos, con su base y pantallas, por id. */
    public Map<Integer, Roles.Adicional> rolesActivos() {
        Map<Integer, Roles.Adicional> m = new HashMap<>();
        roles(jdbc.sql("""
                SELECT COALESCE(string_agg(p.id || ':' || p.base_id || ':' || COALESCE(pp.pantalla, ''), ','), '')
                FROM perfiles p JOIN perfiles b ON b.id = p.base_id AND b.activo LEFT JOIN perfiles_pantallas pp ON pp.perfil_id = p.id
                WHERE p.activo""").query(String.class).single()).forEach(r -> m.put(r.id(), r));
        return m;
    }

    /** Nombres visibles de perfiles y roles (configurables por el administrador). */
    public Map<Integer, String> nombresPerfiles() {
        Map<Integer, String> m = new HashMap<>();
        jdbc.sql("SELECT id, nombre FROM perfiles").query((rs, n) -> m.put(rs.getInt(1), rs.getString(2))).list();
        return m;
    }

    static Usuario fila(ResultSet rs, int n) throws SQLException {
        Set<Perfil> perfiles = EnumSet.noneOf(Perfil.class);
        String ids = rs.getString("perfiles");
        if (ids != null && !ids.isBlank()) {
            perfiles.addAll(Arrays.stream(ids.split(",")).map(Integer::parseInt).map(Perfil::deId)
                    .flatMap(Optional::stream).collect(Collectors.toSet()));
        }
        return new Usuario(rs.getLong("id"), rs.getString("nombre"), rs.getString("documento"), rs.getString("telefono"),
                rs.getString("usuario"), rs.getString("correo"), rs.getString("clave_hash"), rs.getBoolean("activo"),
                rs.getBoolean("clave_definida"), instante(rs, "clave_vence"), rs.getBoolean("debe_cambiar_clave"),
                instante(rs, "clave_actualizada"), rs.getString("token_hash"), instante(rs, "token_vence"), rs.getInt("intentos"),
                instante(rs, "bloqueado_hasta"), instante(rs, "ultimo_ingreso"), rs.getString("alcance_fuerza"),
                (Long) rs.getObject("alcance_punto"), (Long) rs.getObject("alcance_regional"), rs.getBoolean("clave_inicial"),
                Set.copyOf(perfiles), roles(rs.getString("roles_adicionales")));
    }

    /**
     * Roles adicionales a partir de "id:base:PANTALLA,…". Una pantalla que ya no existe en el código se ignora, y un rol cuya base dejó de
     * ser válida no se cuenta: falla cerrado.
     */
    static List<Roles.Adicional> roles(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        Map<Integer, Perfil> bases = new TreeMap<>();
        Map<Integer, Set<Pantallas>> pantallas = new TreeMap<>();
        for (String parte : texto.split(",")) {
            String[] c = parte.split(":", -1);
            if (c.length != 3) {
                continue;
            }
            int id = Integer.parseInt(c[0]);
            Optional<Perfil> base = Perfil.deId(Integer.parseInt(c[1])).filter(Roles.BASES::contains);
            if (base.isEmpty()) {
                LOG.warn("[roles] Rol {} ignorado: su perfil base {} no es válido", c[0], c[1]);
                continue;
            }
            bases.put(id, base.get());
            Set<Pantallas> ps = pantallas.computeIfAbsent(id, k -> EnumSet.noneOf(Pantallas.class));
            Arrays.stream(Pantallas.values()).filter(p -> p.name().equals(c[2])).findFirst().ifPresent(ps::add);
        }
        return bases.entrySet().stream().map(e -> new Roles.Adicional(e.getKey(), e.getValue(), pantallas.get(e.getKey()))).toList();
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
