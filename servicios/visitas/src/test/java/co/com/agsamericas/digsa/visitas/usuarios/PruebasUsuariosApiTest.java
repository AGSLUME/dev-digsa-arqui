package co.com.agsamericas.digsa.visitas.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VA1.5: {@code POST /interno/pruebas/usuarios}, la forma de llenar {@code usuarios_copia} en las pruebas y en local mientras no
 * lleguen los eventos de seguridad (S2). Existe solo con los perfiles {@code local} y {@code prueba} y pide un token de máquina con el
 * alcance {@code visitas:pruebas}. Que no exista en otros perfiles lo prueba {@link InternoPruebasAusenteTest}.
 */
class PruebasUsuariosApiTest extends PruebaApi {

    private static final String RUTA = "/interno/pruebas/usuarios";

    @Autowired
    DirectorioUsuarios directorio;

    private static Map<String, Object> usuario(long uid, String nombre) {
        Map<String, Object> m = new HashMap<>();
        m.put("uid", uid);
        m.put("usuario", "u" + uid);
        m.put("nombre", nombre);
        m.put("documento", "D" + uid);
        m.put("correo", "u" + uid + "@visitas-a.prueba.local");
        m.put("activo", true);
        m.put("perfiles", List.of(2, 3));
        m.put("alcanceFuerza", null);
        m.put("alcancePunto", null);
        m.put("alcanceRegional", null);
        return m;
    }

    private Map<String, Object> fila(long uid) {
        return jdbc.sql("SELECT usuario, nombre, documento, correo, activo, perfiles::text AS perfiles, alcance_fuerza, alcance_punto, "
                + "alcance_regional, version FROM usuarios_copia WHERE uid = :u").param("u", uid).query().singleRow();
    }

    @Test
    @DisplayName("crea el usuario en la copia con todos sus campos y 200 {mensaje}; el directorio ya lo ve")
    void crea() throws Exception {
        long uid = nuevoUid();
        Map<String, Object> cuerpo = usuario(uid, "Usuario Ficticio Uno");
        cuerpo.put("alcanceFuerza", "EJC");
        cuerpo.put("alcancePunto", 77L);
        cuerpo.put("alcanceRegional", 3L);
        post(RUTA, tokenDeMaquina("visitas:pruebas"), cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").isString());

        Map<String, Object> f = fila(uid);
        assertThat(f.get("usuario")).isEqualTo("u" + uid);
        assertThat(f.get("nombre")).isEqualTo("Usuario Ficticio Uno");
        assertThat(f.get("documento")).isEqualTo("D" + uid);
        assertThat(f.get("correo")).isEqualTo("u" + uid + "@visitas-a.prueba.local");
        assertThat(f.get("activo")).isEqualTo(true);
        assertThat(f.get("perfiles")).isEqualTo("{2,3}");
        assertThat(f.get("alcance_fuerza")).isEqualTo("EJC");
        assertThat(f.get("alcance_punto")).isEqualTo(77L);
        assertThat(f.get("alcance_regional")).isEqualTo(3L);
        assertThat(((Number) f.get("version")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(directorio.porId(uid)).isPresent();
        assertThat(directorio.esAuditor(uid)).isTrue();
    }

    @Test
    @DisplayName("el mismo uid actualiza (upsert): cambia los campos, no duplica y la versión sube en 1")
    void actualiza() throws Exception {
        long uid = nuevoUid();
        post(RUTA, tokenDeMaquina("visitas:pruebas"), usuario(uid, "Nombre inicial")).andExpect(status().isOk());
        long v1 = ((Number) fila(uid).get("version")).longValue();

        Map<String, Object> cambio = usuario(uid, "Nombre cambiado");
        cambio.put("activo", false);
        cambio.put("perfiles", List.of(7));
        cambio.put("alcancePunto", 12L);
        post(RUTA, tokenDeMaquina("visitas:pruebas"), cambio).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").isString());

        Map<String, Object> f = fila(uid);
        assertThat(f.get("nombre")).isEqualTo("Nombre cambiado");
        assertThat(f.get("activo")).isEqualTo(false);
        assertThat(f.get("perfiles")).isEqualTo("{7}");
        assertThat(f.get("alcance_punto")).isEqualTo(12L);
        assertThat(((Number) f.get("version")).longValue()).isEqualTo(v1 + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Long.class).single()).isEqualTo(1L);

        // un tercer envío con alcances nulos los borra
        post(RUTA, tokenDeMaquina("visitas:pruebas"), usuario(uid, "Nombre cambiado")).andExpect(status().isOk());
        Map<String, Object> g = fila(uid);
        assertThat(g.get("alcance_punto")).isNull();
        assertThat(((Number) g.get("version")).longValue()).isEqualTo(v1 + 2);
    }

    @Test
    @DisplayName("sin token: 401; con token de persona: 403; con token de máquina de otro alcance: 403; nada se escribe")
    void autorizacion() throws Exception {
        long uid = nuevoUid();
        post(RUTA, null, usuario(uid, "Sin token")).andExpect(status().isUnauthorized());
        post(RUTA, token(7_400_000_001L, "CATALOGOS", "MAESTROS"), usuario(uid, "Persona")).andExpect(status().isForbidden());
        post(RUTA, tokenDeMaquina("visitas:otro"), usuario(uid, "Otro alcance")).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("un cuerpo sin uid o sin nombre es un error del cliente (4xx) y no escribe")
    void cuerpoIncompleto() throws Exception {
        long uid = nuevoUid();
        Map<String, Object> sinUid = usuario(uid, "Sin uid");
        sinUid.remove("uid");
        post(RUTA, tokenDeMaquina("visitas:pruebas"), sinUid).andExpect(status().is4xxClientError());
        Map<String, Object> sinNombre = usuario(uid, "x");
        sinNombre.put("nombre", null);
        post(RUTA, tokenDeMaquina("visitas:pruebas"), sinNombre).andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Long.class).single()).isZero();
    }
}
