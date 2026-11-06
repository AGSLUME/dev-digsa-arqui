package co.com.agsamericas.digsa.visitas.usuarios;

import co.com.agsamericas.digsa.reglas.Perfil;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lo que visitas necesita de los usuarios, leído de la copia local {@code usuarios_copia} (ADR-002, D2). Reemplaza las consultas del
 * monolito a {@code usuarios} y {@code usuarios_perfiles}. La copia es eventual: falla cerrado (un usuario que no está no cuenta como
 * auditor activo). Lo usan también las áreas de VISITAS B.
 */
@Component
public class DirectorioUsuarios {

    /** Un usuario de la copia. El documento es dato personal: nunca va a logs ni a la bitácora. */
    public record UsuarioCopia(long uid, String usuario, String nombre, String documento, String correo, boolean activo, List<Integer> perfiles) {

        public boolean tiene(Perfil p) {
            return perfiles.contains((int) p.id());
        }
    }

    private static final String COLUMNAS = "uid, usuario, nombre, documento, correo, activo, perfiles";

    private final JdbcClient jdbc;

    public DirectorioUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UsuarioCopia> porId(long uid) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios_copia WHERE uid = :u").param("u", uid).query(DirectorioUsuarios::fila).optional();
    }

    /** Tiene el perfil Auditor (activo o no), como {@code esAuditor} del monolito. */
    public boolean esAuditor(long uid) {
        return porId(uid).map(u -> u.tiene(Perfil.AUDITOR)).orElse(false);
    }

    /** Activo y con el perfil Auditor, como {@code esAuditorActivo} del monolito. */
    public boolean auditorActivo(long uid) {
        return porId(uid).map(u -> u.activo() && u.tiene(Perfil.AUDITOR)).orElse(false);
    }

    /** Todos los usuarios con el perfil Auditor, activos primero y por nombre (pestaña Auditores de Maestros). */
    public List<UsuarioCopia> auditores() {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios_copia WHERE :p = ANY (perfiles) ORDER BY activo DESC, nombre")
                .param("p", (short) Perfil.AUDITOR.id()).query(DirectorioUsuarios::fila).list();
    }

    /** Correos de los coordinadores activos, sin repetir (aviso de una solicitud nueva). */
    public List<String> correosCoordinadoresActivos() {
        return jdbc.sql("""
                SELECT DISTINCT lower(correo) FROM usuarios_copia
                WHERE activo AND :p = ANY (perfiles) AND correo IS NOT NULL AND correo <> '' ORDER BY 1""")
                .param("p", (short) Perfil.COORDINADOR.id()).query(String.class).list();
    }

    /** Usuarios (activos o no) cuyo alcance es la fuerza: cuentan para «la fuerza está en uso». */
    public long usuariosConAlcanceFuerza(String fuerza) {
        return jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE alcance_fuerza = :f").param("f", fuerza).query(Long.class).single();
    }

    public long usuariosActivosConAlcanceRegional(long regionalId) {
        return jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE alcance_regional = :r AND activo").param("r", regionalId)
                .query(Long.class).single();
    }

    public long usuariosActivosConAlcancePunto(long puntoId) {
        return jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE alcance_punto = :p AND activo").param("p", puntoId)
                .query(Long.class).single();
    }

    static UsuarioCopia fila(ResultSet rs, int n) throws SQLException {
        return new UsuarioCopia(rs.getLong("uid"), rs.getString("usuario"), rs.getString("nombre"), rs.getString("documento"),
                rs.getString("correo"), rs.getBoolean("activo"), perfiles(rs.getArray("perfiles")));
    }

    private static List<Integer> perfiles(Array a) throws SQLException {
        List<Integer> r = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                if (o instanceof Number num) {
                    r.add(num.intValue());
                }
            }
        }
        return List.copyOf(r);
    }
}
