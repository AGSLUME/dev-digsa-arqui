package co.com.agsamericas.digsa.seguridad.datos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S1.1 (sin transacción: cada violación esperada abortaría la transacción de la prueba; los datos llevan sufijo único): el esquema de seguridad (Flyway) conserva las reglas de datos del monolito y agrega la sesión única por índice. */
class EsquemaTest extends PruebaIntegracion {

    @Autowired
    JdbcClient jdbc;

    @Test
    void losPerfilesBaseSonLosDelEnumConLosMismosId() {
        List<Integer> ids = jdbc.sql("SELECT id FROM perfiles WHERE base_id IS NULL ORDER BY id").query(Integer.class).list();
        assertThat(ids).containsExactlyElementsOf(Arrays.stream(Perfil.values()).map(p -> (int) p.id()).sorted().toList());
        assertThat(jdbc.sql("SELECT activo FROM perfiles WHERE id = 4").query(Boolean.class).single()).isFalse();
    }

    @Test
    void laAplicacionUsaSuRolSinDdlYElEsquemaEsSeguridad() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("seguridad");
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE intrusa (id int)").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM usuarios WHERE id = -1").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("seguridad");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'seguridad' AND table_name = 'usuarios'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void unRolAdicionalNoDerivaDelAdministradorNiDelAuditor() {
        assertThatThrownBy(() -> rol(1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rol(3)).isInstanceOf(DataIntegrityViolationException.class);
        rol(2);
    }

    @Test
    void unRolAdicionalSiempreTienePerfilBase() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO perfiles (id, nombre) VALUES (nextval('perfiles_adicionales_id_seq'), 'Rol sin base')")
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void soloHayUnaClaveDeFirmaVigente() {
        jdbc.sql("INSERT INTO jwk (kid, publica, privada_cifrada, creada) VALUES ('k1', '{}', '\\x00', now())").update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO jwk (kid, publica, privada_cifrada, creada) VALUES ('k2', '{}', '\\x00', now())")
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("DELETE FROM jwk WHERE kid = 'k1'").update();
    }

    @Test
    void elNombreDePerfilEsUnicoSinDistinguirMayusculas() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO perfiles (id, nombre, base_id) VALUES (nextval('perfiles_adicionales_id_seq'), "
                + "'COORDINADOR', 2)").update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elUsuarioYElCorreoTienenElFormatoDelMonolito() {
        assertThatThrownBy(() -> usuario("Ana.Prueba", "ana@seguridad.prueba.local")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> usuario("ana.prueba", "Ana@seguridad.prueba.local")).isInstanceOf(DataIntegrityViolationException.class);
        usuario("ana.prueba", "ana@seguridad.prueba.local");
    }

    @Test
    void sePuedeInsertarUnUsuarioConElIdDelMonolito() {
        jdbc.sql("INSERT INTO usuarios (id, nombre, usuario, correo, clave_hash) VALUES (4242, 'Usuario Migrado', 'migrado', "
                + "'migrado@seguridad.prueba.local', 'x')").update();
        assertThat(jdbc.sql("SELECT usuario FROM usuarios WHERE id = 4242").query(String.class).single()).isEqualTo("migrado");
    }

    @Test
    void unUsuarioTieneComoMaximoUnaSesionAbierta() {
        long id = usuario("bruno.prueba", "bruno@seguridad.prueba.local");
        sesion(id, null);
        assertThatThrownBy(() -> sesion(id, null)).isInstanceOf(DataIntegrityViolationException.class);
        // Las cerradas no cuentan
        jdbc.sql("UPDATE sesiones SET cerrada = now(), motivo = 'salida' WHERE usuario_id = :u").param("u", id).update();
        sesion(id, null);
    }

    @Test
    void unaSesionCerradaLlevaSuMotivo() {
        long id = usuario("carla.prueba", "carla@seguridad.prueba.local");
        assertThatThrownBy(() -> sesion(id, "salida")).isInstanceOf(DataIntegrityViolationException.class);
        // Y la otra mitad: un motivo sin fecha de cierre tampoco
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO sesiones (id, usuario_id, inicio, ultimo, motivo) VALUES (:id, :u, now(), now(), 'salida')")
                .param("id", UUID.randomUUID()).param("u", id).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void rol(int base) {
        jdbc.sql("INSERT INTO perfiles (id, nombre, base_id) VALUES (nextval('perfiles_adicionales_id_seq'), :n, :b)")
                .param("n", "Rol " + UUID.randomUUID().toString().substring(0, 8)).param("b", base).update();
    }

    private long usuario(String usuario, String correo) {
        String s = Long.toString(System.nanoTime() % 100000);
        usuario = usuario + s;
        correo = correo.replace("@", s + "@");
        return jdbc.sql("INSERT INTO usuarios (nombre, usuario, correo, clave_hash) VALUES ('Nombre Ficticio', :u, :c, 'x') RETURNING id")
                .param("u", usuario).param("c", correo).query(Long.class).single();
    }

    /** Sesión abierta (cerrada = null) o con cerrada = ahora y sin motivo cuando {@code soloCerrada} no es null. */
    private void sesion(long usuarioId, String soloCerrada) {
        OffsetDateTime ahora = OffsetDateTime.now();
        jdbc.sql("INSERT INTO sesiones (id, usuario_id, inicio, ultimo, cerrada) VALUES (:id, :u, :a, :a, :c)")
                .param("id", UUID.randomUUID()).param("u", usuarioId).param("a", ahora)
                .param("c", soloCerrada == null ? null : ahora).update();
    }
}
