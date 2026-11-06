package co.com.agsamericas.digsa.visitas.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.comun.eventos.Sobre;
import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Copia de usuarios (D2): la foto de usuario.* y cuenta.* se aplica con la regla de versión y la lista blanca de tipos. */
class CopiaUsuariosTest extends PruebaIntegracion {

    private static final AtomicLong UIDS = new AtomicLong(7_000_000_000L + System.nanoTime() % 1_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    CopiaUsuarios copia;

    static Map<String, Object> foto(long uid, String nombre, boolean activo, List<String> perfiles, String documento, String alcanceTipo,
                                    Object fuerza, Object punto, Object regional) {
        Map<String, Object> alcance = new LinkedHashMap<>();
        alcance.put("tipo", alcanceTipo);
        alcance.put("fuerza", fuerza);
        alcance.put("punto", punto);
        alcance.put("regional", regional);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("uid", uid);
        d.put("usuario", "u" + uid);
        d.put("nombre", nombre);
        d.put("documento", documento);
        d.put("correo", "u" + uid + "@visitas-a.prueba.local");
        d.put("activo", activo);
        d.put("bloqueado", false);
        d.put("perfiles", perfiles);
        d.put("roles", List.of());
        d.put("pantallas", List.of("INICIO"));
        d.put("alcance", alcance);
        d.put("asignados", List.of());
        return d;
    }

    static Sobre sobre(String tipo, long version, Map<String, Object> datos) {
        return new Sobre(UUID.randomUUID(), tipo, version, Instant.now(), "seguridad", null, JSON.valueToTree(datos));
    }

    private Map<String, Object> fila(long uid) {
        return jdbc.sql("SELECT nombre, documento, activo, perfiles::text AS perfiles, alcance_fuerza, alcance_punto, alcance_regional, version "
                + "FROM usuarios_copia WHERE uid = :u").param("u", uid).query().singleRow();
    }

    @Test
    void creaLaFilaConLosPerfilesPorIdYElAlcance() {
        long uid = UIDS.incrementAndGet();
        assertThat(copia.aplicar(sobre("usuario.creado", 1, foto(uid, "Auditora Ficticia", true, List.of("AUDITOR"), "99887766", "equipo",
                null, null, null)))).isTrue();
        Map<String, Object> f = fila(uid);
        assertThat(f.get("nombre")).isEqualTo("Auditora Ficticia");
        assertThat(f.get("documento")).isEqualTo("99887766");
        assertThat(f.get("perfiles")).isEqualTo("{3}");
        assertThat(f.get("version")).isEqualTo(1L);

        long esm = UIDS.incrementAndGet();
        copia.aplicar(sobre("usuario.creado", 1, foto(esm, "Usuario ESM", true, List.of("ESM", "PERFIL_QUE_NO_EXISTE"), null, "esm", null, 77, null)));
        assertThat(fila(esm).get("perfiles")).as("un perfil desconocido no da nada").isEqualTo("{7}");
        assertThat(fila(esm).get("alcance_punto")).isEqualTo(77L);
        assertThat(fila(esm).get("documento")).as("el documento solo llega para los auditores").isNull();
    }

    @Test
    void unaVersionViejaORepetidaNoPisaLaNueva() {
        long uid = UIDS.incrementAndGet();
        copia.aplicar(sobre("usuario.creado", 1, foto(uid, "Versión 1", true, List.of("AUDITOR"), null, "equipo", null, null, null)));
        assertThat(copia.aplicar(sobre("usuario.actualizado", 5, foto(uid, "Versión 5", true, List.of("AUDITOR"), null, "equipo", null, null,
                null)))).isTrue();
        assertThat(copia.aplicar(sobre("usuario.actualizado", 3, foto(uid, "Versión 3 (tarde)", false, List.of(), null, "ninguno", null, null,
                null)))).as("llegó tarde").isFalse();
        assertThat(copia.aplicar(sobre("usuario.actualizado", 5, foto(uid, "Versión 5 repetida", false, List.of(), null, "ninguno", null, null,
                null)))).as("misma versión").isFalse();
        assertThat(fila(uid).get("nombre")).isEqualTo("Versión 5");
        assertThat(fila(uid).get("activo")).isEqualTo(true);
        assertThat(fila(uid).get("version")).isEqualTo(5L);
    }

    @Test
    void inactivarYCambiarPermisosActualizanLaCopia() {
        long uid = UIDS.incrementAndGet();
        copia.aplicar(sobre("usuario.creado", 1, foto(uid, "Sanidad Ficticia", true, List.of("SANIDAD"), null, "fuerza", "EJC", null, null)));
        copia.aplicar(sobre("usuario.permisos_cambiados", 2, foto(uid, "Sanidad Ficticia", true, List.of("SANIDAD"), null, "fuerza", "ARC", null,
                null)));
        assertThat(fila(uid).get("alcance_fuerza")).isEqualTo("ARC");
        copia.aplicar(sobre("usuario.estado_cambiado", 3, foto(uid, "Sanidad Ficticia", false, List.of("SANIDAD"), null, "fuerza", "ARC", null,
                null)));
        assertThat(fila(uid).get("activo")).isEqualTo(false);
        copia.aplicar(sobre("cuenta.bloqueada", 4, foto(uid, "Sanidad Ficticia", false, List.of("SANIDAD"), null, "fuerza", "ARC", null, null)));
        assertThat(fila(uid).get("version")).isEqualTo(4L);
    }

    @Test
    void unTipoFueraDeLaListaBlancaSeDescartaSinTocarNada() {
        long uid = UIDS.incrementAndGet();
        assertThat(copia.aplicar(sobre("sesion.cerrada", 9, foto(uid, "No debe quedar", true, List.of("AUDITOR"), null, "equipo", null, null,
                null)))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Long.class).single()).isZero();
    }

    @Test
    void unaFotoSinUidValidoSeRechaza() {
        Map<String, Object> mala = foto(1, "x", true, List.of(), null, "ninguno", null, null, null);
        mala.put("uid", "no-es-numero");
        assertThatThrownBy(() -> copia.aplicar(sobre("usuario.creado", 1, mala))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("no-es-numero");
    }
}
