package co.com.agsamericas.digsa.visitas.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VA1.5: el puerto {@link DirectorioUsuarios} (lectura de {@code usuarios_copia}) que usan catálogos, maestros y, más adelante, la
 * agenda. Los usuarios son ficticios y la base se comparte: se verifican presencias y conteos de códigos o ids propios de cada prueba.
 * Perfiles: 2 = Coordinador, 3 = Auditor, 7 = ESM, 9 = OPL regional ({@code reglas/Perfil}).
 */
class DirectorioUsuariosTest extends PruebaApi {

    @Autowired
    DirectorioUsuarios directorio;

    @Test
    @DisplayName("porId devuelve el usuario con uid, usuario, nombre, documento, correo, activo y perfiles; vacío si no existe")
    void porId() {
        long uid = sembrarUsuario("Persona Ficticia", true, 2, 3);
        assertThat(directorio.porId(uid)).hasValueSatisfying(u -> {
            assertThat(u.uid()).isEqualTo(uid);
            assertThat(u.usuario()).isEqualTo("u" + uid);
            assertThat(u.nombre()).isEqualTo("Persona Ficticia");
            assertThat(u.documento()).isEqualTo("D" + uid);
            assertThat(u.correo()).isEqualTo("u" + uid + "@visitas-a.prueba.local");
            assertThat(u.activo()).isTrue();
            assertThat(u.perfiles()).containsExactlyInAnyOrder(2, 3);
        });
        assertThat(directorio.porId(1L)).as("uid inexistente").isEmpty();
        long inactivo = sembrarUsuario("Persona Inactiva", false, 7);
        assertThat(directorio.porId(inactivo)).hasValueSatisfying(u -> assertThat(u.activo()).isFalse());
    }

    @Test
    @DisplayName("esAuditor mira el perfil 3 aunque esté inactivo; auditorActivo exige además activo")
    void auditores() {
        long activo = sembrarAuditor("Auditor activo", true);
        long inactivo = sembrarAuditor("Auditor inactivo", false);
        long mixto = sembrarUsuario("Coordinador y auditor", true, 2, 3);
        long coordinador = sembrarUsuario("Solo coordinador", true, 2);
        long sinPerfiles = sembrarUsuario("Sin perfiles", true);

        assertThat(directorio.esAuditor(activo)).isTrue();
        assertThat(directorio.auditorActivo(activo)).isTrue();
        assertThat(directorio.esAuditor(inactivo)).isTrue();
        assertThat(directorio.auditorActivo(inactivo)).isFalse();
        assertThat(directorio.esAuditor(mixto)).isTrue();
        assertThat(directorio.auditorActivo(mixto)).isTrue();
        assertThat(directorio.esAuditor(coordinador)).isFalse();
        assertThat(directorio.auditorActivo(coordinador)).isFalse();
        assertThat(directorio.esAuditor(sinPerfiles)).isFalse();
        assertThat(directorio.esAuditor(1L)).as("uid inexistente").isFalse();
        assertThat(directorio.auditorActivo(1L)).isFalse();

        jdbc.sql("UPDATE usuarios_copia SET activo = false, version = version + 1 WHERE uid = :u").param("u", activo).update();
        assertThat(directorio.auditorActivo(activo)).as("lee la copia vigente, sin caché").isFalse();
    }

    @Test
    @DisplayName("correosCoordinadoresActivos: solo coordinadores activos con correo; ni inactivos, ni otros perfiles, ni nulos")
    void correosDeCoordinadores() {
        long activo = sembrarUsuario("Coordinador activo", true, 2);
        long inactivo = sembrarUsuario("Coordinador inactivo", false, 2);
        long auditor = sembrarAuditor("Auditor", true);
        long sinCorreo = sembrarUsuario("Coordinador sin correo", true, 2);
        jdbc.sql("UPDATE usuarios_copia SET correo = NULL WHERE uid = :u").param("u", sinCorreo).update();
        long mixto = sembrarUsuario("Coordinador y auditor", true, 2, 3);

        assertThat(directorio.correosCoordinadoresActivos())
                .contains("u" + activo + "@visitas-a.prueba.local", "u" + mixto + "@visitas-a.prueba.local")
                .doesNotContain("u" + inactivo + "@visitas-a.prueba.local", "u" + auditor + "@visitas-a.prueba.local")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("usuariosConAlcanceFuerza cuenta todos los usuarios (activos o no) con esa fuerza")
    void alcanceDeFuerza() {
        String f = letras(5);
        sembrarUsuario("Sanidad activa", true, new int[]{6}, f, null, null);
        sembrarUsuario("Sanidad inactiva", false, new int[]{6}, f, null, null);
        sembrarUsuario("Otra fuerza", true, new int[]{6}, letras(5), null, null);
        assertThat(directorio.usuariosConAlcanceFuerza(f)).isEqualTo(2L);
        assertThat(directorio.usuariosConAlcanceFuerza(letras(5))).isZero();
    }

    @Test
    @DisplayName("usuariosActivosConAlcanceRegional cuenta solo los activos de esa regional")
    void alcanceRegional() {
        long regional = 9_000_000L + secuencia() % 1_000_000L;
        sembrarUsuario("OPL regional activo", true, new int[]{9}, null, null, regional);
        long segundo = sembrarUsuario("OPL regional activo 2", true, new int[]{9}, null, null, regional);
        sembrarUsuario("OPL regional inactivo", false, new int[]{9}, null, null, regional);
        sembrarUsuario("OPL de otra regional", true, new int[]{9}, null, null, regional + 1);
        assertThat(directorio.usuariosActivosConAlcanceRegional(regional)).isEqualTo(2L);
        jdbc.sql("UPDATE usuarios_copia SET activo = false, version = version + 1 WHERE uid = :u").param("u", segundo).update();
        assertThat(directorio.usuariosActivosConAlcanceRegional(regional)).isEqualTo(1L);
        assertThat(directorio.usuariosActivosConAlcanceRegional(regional + 5)).isZero();
    }

    @Test
    @DisplayName("usuariosActivosConAlcancePunto cuenta solo los activos de ese punto")
    void alcanceDePunto() {
        long punto = 9_000_000L + secuencia() % 1_000_000L;
        sembrarUsuario("ESM activo", true, new int[]{7}, null, punto, null);
        sembrarUsuario("ESM inactivo", false, new int[]{7}, null, punto, null);
        sembrarUsuario("ESM de otro punto", true, new int[]{7}, null, punto + 1, null);
        assertThat(directorio.usuariosActivosConAlcancePunto(punto)).isEqualTo(1L);
        assertThat(directorio.usuariosActivosConAlcancePunto(punto + 5)).isZero();
    }
}
