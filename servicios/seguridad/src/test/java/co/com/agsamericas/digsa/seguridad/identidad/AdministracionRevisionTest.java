package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.BitacoraCapturada;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Correcciones de la revisión de S2.1 (revisor-java y revisor-bd): bitácora con antes y después, último administrador, orden de permisos. */
class AdministracionRevisionTest extends AdministracionApiBase {

    private static final String ULTIMO = "Debe quedar al menos un usuario Administrador activo.";

    @Autowired
    BitacoraCapturada bitacora;

    @Autowired
    ServicioUsuarios servicio;

    @Test
    @DisplayName("A1: cambiar los minutos de inactividad queda en la bitácora con el antes y el después")
    void parametroEnBitacora() throws Exception {
        int original = json(leer("/api/usuarios/seguridad")).path("minutosSesion").asInt();
        int nuevo = original == 17 ? 18 : 17;
        try {
            poner("/api/usuarios/seguridad", Map.of("minutosSesion", nuevo)).andExpect(status().isOk());
            assertThat(bitacora.ultimo("parametro_editado")).isEqualTo("Parámetro de seguridad: sesion_minutos: «" + original + "» → «" + nuevo + "»");
            assertThat(bitacora.de("parametro_editado").getLast().usuario()).isEqualTo(admin.usuario());
        } finally {
            poner("/api/usuarios/seguridad", Map.of("minutosSesion", original)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("A2: editar un usuario deja el antes y el después; los datos personales solo como «modificado»")
    void edicionConAntesYDespues() throws Exception {
        String u = unico("foto");
        long id = crearUsuarioPorApi(datosUsuario(u, Perfil.COORDINADOR.id()));
        Map<String, Object> d = datosUsuario(u, Perfil.DIGSA.id());
        d.put("nombre", "Otra Persona Ficticia");
        poner("/api/usuarios/" + id, d).andExpect(status().isOk());
        String detalle = bitacora.ultimo("usuario_editado");
        assertThat(detalle).contains("nombre: modificado").contains("perfiles: «" + Perfil.COORDINADOR.id() + "» → «" + Perfil.DIGSA.id() + "»")
                .doesNotContain("Otra Persona Ficticia");

        poner("/api/usuarios/" + id + "/estado", Map.of("activo", false)).andExpect(status().isOk());
        assertThat(bitacora.ultimo("usuario_estado")).contains("activo: «true» → «false»");
    }

    @Test
    @DisplayName("A2: editar un rol deja el antes y el después de nombre y pantallas")
    void rolConAntesYDespues() throws Exception {
        String nombre = unico("Rol foto ");
        int id = json(publicar("/api/usuarios/roles", Map.of("nombre", nombre, "baseId", Perfil.DIGSA.id(), "pantallas", List.of("CALENDARIO")))
                .andExpect(status().isCreated())).path("id").asInt();
        poner("/api/usuarios/roles/" + id, Map.of("nombre", nombre, "baseId", Perfil.DIGSA.id(), "pantallas", List.of("CALENDARIO", "REPORTES")))
                .andExpect(status().isOk());
        assertThat(bitacora.ultimo("rol_editado")).isEqualTo("Rol " + id + ": pantallas: «CALENDARIO» → «CALENDARIO,REPORTES»");
    }

    @Test
    @DisplayName("M1: PUT /api/usuarios/0 no crea un usuario: 404")
    void editarConIdCeroNoCrea() throws Exception {
        String u = unico("cero");
        poner("/api/usuarios/0", datosUsuario(u, Perfil.COORDINADOR.id())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El usuario no existe."));
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isZero();
    }

    @Test
    @DisplayName("M3: sin la pantalla USUARIOS, un cuerpo inválido da 403 (no las reglas de validación)")
    void permisoAntesQueValidacion() throws Exception {
        RequestPostProcessor coord = sesionDe(datos.usuario(Perfil.COORDINADOR));
        publicar(coord, "/api/usuarios", Map.of()).andExpect(status().isForbidden());
        publicar(coord, "/api/usuarios/roles", Map.of()).andExpect(status().isForbidden());
        poner(coord, "/api/usuarios/1/estado", Map.of()).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("M2: no se inactiva ni se le quita el perfil al último Administrador activo")
    void ultimoAdministrador() throws Exception {
        List<Long> otros = soloQueda(admin.id());
        try {
            var noAdmin = datos.usuario(Perfil.COORDINADOR);
            Permisos.Quien alguien = new Permisos.Quien(noAdmin.id(), noAdmin.usuario());
            assertThatThrownBy(() -> servicio.cambiarEstado(admin.id(), false, alguien)).isInstanceOf(ErrorNegocio.class).hasMessage(ULTIMO);
            assertThatThrownBy(() -> servicio.guardar(new ServicioUsuarios.Datos(admin.id(), "Administrador Ficticio", "12345678", null, admin.usuario(),
                    admin.correo(), List.of(Perfil.DIGSA.id()), null, null, null, null), alguien))
                    .isInstanceOf(ErrorNegocio.class).hasMessage(ULTIMO);
            assertThat(contar("SELECT count(*) FROM usuarios WHERE id = ? AND activo", admin.id())).isEqualTo(1);
        } finally {
            reactivar(otros);
        }
    }

    @Test
    @DisplayName("M2: dos administradores que se inactivan mutuamente a la vez: solo uno gana y queda un Administrador activo")
    void inactivacionCruzada() throws Exception {
        var otro = administrador();
        RequestPostProcessor comoOtro = sesionDe(otro);
        List<Long> resto = soloQueda(admin.id(), otro.id());
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tareas = List.of(
                    () -> poner(comoAdmin, "/api/usuarios/" + otro.id() + "/estado", Map.of("activo", false)).andReturn().getResponse().getStatus(),
                    () -> poner(comoOtro, "/api/usuarios/" + admin.id() + "/estado", Map.of("activo", false)).andReturn().getResponse().getStatus());
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> f : hilos.invokeAll(tareas)) {
                estados.add(f.get());
            }
            // El perdedor recibe 422 (último administrador) o 401 (su propia sesión ya se cerró por la inactivación)
            assertThat(estados).containsOnlyOnce(200);
            assertThat(contar("SELECT count(*) FROM usuarios WHERE id IN (?, ?) AND activo", admin.id(), otro.id())).isEqualTo(1);
        } finally {
            hilos.shutdownNow();
            jdbc.sql("UPDATE usuarios SET activo = true WHERE id IN (:a, :b)").param("a", admin.id()).param("b", otro.id()).update();
            reactivar(resto);
        }
    }

    /** Deja como únicos Administradores activos a {@code quedan} (los demás, de otras pruebas, se inactivan y se devuelven). */
    private List<Long> soloQueda(Long... quedan) {
        List<Long> otros = jdbc.sql("""
                SELECT u.id FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id
                WHERE up.perfil_id = :adm AND u.activo AND NOT (u.id = ANY(:q))""")
                .param("adm", Perfil.ADMINISTRADOR.id()).param("q", quedan).query(Long.class).list();
        if (!otros.isEmpty()) {
            jdbc.sql("UPDATE usuarios SET activo = false WHERE id IN (:ids)").param("ids", otros).update();
        }
        return otros;
    }

    private void reactivar(List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE usuarios SET activo = true WHERE id IN (:ids)").param("ids", ids).update();
        }
    }
}
