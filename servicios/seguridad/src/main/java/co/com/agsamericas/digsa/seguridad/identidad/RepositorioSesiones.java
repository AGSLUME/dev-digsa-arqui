package co.com.agsamericas.digsa.seguridad.identidad;

import static co.com.agsamericas.digsa.seguridad.identidad.RepositorioUsuarios.instante;
import static co.com.agsamericas.digsa.seguridad.identidad.RepositorioUsuarios.odt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sesiones de LUME (tabla {@code sesiones}): una fila por ingreso completo; su id es el claim {@code sid} del token (ADR-004).
 * Reemplaza {@code usuarios.sesion_huella / sesion_ultimo} del monolito. «Una sola sesión activa por usuario» la garantiza el índice
 * único parcial {@code uq_sesiones_abierta}; aquí se aplica además la decisión 7.1: mientras la sesión abierta tenga actividad dentro
 * del plazo de inactividad, un ingreso nuevo se rechaza (no reemplaza a la anterior).
 */
@Repository
public class RepositorioSesiones {

    /** Por qué se cerró una sesión (los mismos {@code motivo} que el monolito devuelve en el 401, más {@code salida}). */
    public enum Motivo {
        SALIDA, INACTIVIDAD, INACTIVO, CLAVE, CERRADA, REEMPLAZADA, CLAVE_VENCIDA, CAMBIO_CLAVE, BLOQUEADO;

        public String valor() {
            return name().toLowerCase();
        }

        public static Motivo de(String valor) {
            return valueOf(valor.toUpperCase());
        }
    }

    /** Una fila de {@code sesiones}. {@code cerrada} y {@code motivo} son nulos mientras está abierta. */
    public record Fila(UUID id, long usuarioId, Instant inicio, Instant ultimo, Instant cerrada, Motivo motivo) {
        public boolean abierta() {
            return cerrada == null;
        }
    }

    private static final String COLUMNAS = "id, usuario_id, inicio, ultimo, cerrada, motivo";

    private final JdbcClient jdbc;

    public RepositorioSesiones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** ¿Hay una sesión abierta del usuario con actividad dentro del plazo? (decisión 7.1) */
    public boolean bloqueaNuevoIngreso(long usuarioId, Instant ahora, int minutos) {
        return jdbc.sql("SELECT count(*) FROM sesiones WHERE usuario_id = :u AND cerrada IS NULL AND ultimo > :limite")
                .param("u", usuarioId).param("limite", odt(ahora.minusSeconds(60L * minutos))).query(Long.class).single() > 0;
    }

    /**
     * Bloquea la fila del usuario hasta el fin de la transacción (la apertura de su sesión se serializa). Lo usa
     * {@link ServicioSesiones#abrir}, que abre la transacción.
     */
    void bloquearUsuario(long usuarioId) {
        jdbc.sql("SELECT id FROM usuarios WHERE id = :u FOR UPDATE").param("u", usuarioId).query(Long.class).optional();
    }

    /** Cierra por inactividad la sesión abierta del usuario que ya superó el plazo. @return las que cerró */
    List<UUID> cerrarInactivas(long usuarioId, Instant ahora, int minutos) {
        return jdbc.sql("""
                UPDATE sesiones SET cerrada = :ahora, motivo = 'inactividad'
                WHERE usuario_id = :u AND cerrada IS NULL AND ultimo <= :limite RETURNING id""")
                .param("ahora", odt(ahora)).param("u", usuarioId).param("limite", odt(ahora.minusSeconds(60L * minutos)))
                .query(UUID.class).list();
    }

    /**
     * Abre la sesión nueva solo si no queda otra abierta (índice único parcial: dos ingresos casi simultáneos no pasan los dos).
     *
     * @return el id de la sesión nueva (el {@code sid}), o vacío si otra sesión sigue abierta
     */
    Optional<UUID> insertar(long usuarioId, Instant ahora, String ip) {
        UUID id = UUID.randomUUID();
        int creadas = jdbc.sql("""
                INSERT INTO sesiones (id, usuario_id, inicio, ultimo, ip) VALUES (:id, :u, :ahora, :ahora, :ip)
                ON CONFLICT (usuario_id) WHERE cerrada IS NULL DO NOTHING""")
                .param("id", id).param("u", usuarioId).param("ahora", odt(ahora)).param("ip", ip).update();
        return creadas == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<Fila> porId(UUID id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM sesiones WHERE id = :id").param("id", id).query(RepositorioSesiones::fila).optional();
    }

    public Optional<Fila> abiertaDe(long usuarioId) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM sesiones WHERE usuario_id = :u AND cerrada IS NULL").param("u", usuarioId)
                .query(RepositorioSesiones::fila).optional();
    }

    /** Actividad de la sesión (nunca hacia atrás). @return false si ya no está abierta */
    public boolean tocar(UUID id, Instant cuando) {
        return jdbc.sql("UPDATE sesiones SET ultimo = GREATEST(ultimo, :t) WHERE id = :id AND cerrada IS NULL")
                .param("t", odt(cuando)).param("id", id).update() == 1;
    }

    /**
     * La sesión pasa a contar desde {@code desde}: tras cambiar la propia contraseña, la regla «la contraseña cambió después de que empezó
     * la sesión» no debe cerrar la sesión desde la que se hizo el cambio.
     */
    public void renovarInicio(UUID id, Instant desde) {
        jdbc.sql("UPDATE sesiones SET inicio = GREATEST(inicio, :d) WHERE id = :id AND cerrada IS NULL").param("d", odt(desde)).param("id", id)
                .update();
    }

    /** Cierra la sesión si sigue abierta. @return true si la cerró esta llamada */
    public boolean cerrar(UUID id, Motivo motivo, Instant ahora) {
        return jdbc.sql("UPDATE sesiones SET cerrada = :ahora, motivo = :m WHERE id = :id AND cerrada IS NULL")
                .param("ahora", odt(ahora)).param("m", motivo.valor()).param("id", id).update() == 1;
    }

    /** Cierra la sesión abierta del usuario, si la hay (administrador, inactivación, cambio de contraseña, bloqueo). */
    public List<UUID> cerrarDeUsuario(long usuarioId, Motivo motivo, Instant ahora) {
        return jdbc.sql("UPDATE sesiones SET cerrada = :ahora, motivo = :m WHERE usuario_id = :u AND cerrada IS NULL RETURNING id")
                .param("ahora", odt(ahora)).param("m", motivo.valor()).param("u", usuarioId).query(UUID.class).list();
    }

    private static Fila fila(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String m = rs.getString("motivo");
        return new Fila(rs.getObject("id", UUID.class), rs.getLong("usuario_id"), instante(rs, "inicio"), instante(rs, "ultimo"),
                instante(rs, "cerrada"), m == null ? null : Motivo.de(m));
    }
}
