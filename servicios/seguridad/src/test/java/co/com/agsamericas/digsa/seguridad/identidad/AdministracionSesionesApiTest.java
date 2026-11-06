package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.Navegador;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * S2.3: lo que el administrador hace sobre las sesiones y las contraseñas de otros (cerrar sesión, contraseña inicial, desbloquear), el
 * TIME OUT editable (Usuarios › Seguridad) y los nombres de los perfiles. Portado de {@code SesionesYClaveInicialApiTest} y
 * {@code CuentaYSeguridadApiTest} del monolito. Sin bitácora (S3).
 */
class AdministracionSesionesApiTest extends AdministracionApiBase {

    private static final String INICIAL = "Inicial.Asignada.2026";
    private static final String NUEVA = "Propia.Del.Usuario.2026";

    private String motivoDeCierre(UUID sid) {
        return jdbc.sql("SELECT motivo FROM sesiones WHERE id = :id AND cerrada IS NOT NULL").param("id", sid).query(String.class).single();
    }

    private boolean sesionAbierta(long usuarioId) {
        return contar("SELECT count(*) FROM sesiones WHERE usuario_id = ? AND cerrada IS NULL", usuarioId) > 0;
    }

    private ResultActions paso1(Navegador n, String usuario, String clave) throws Exception {
        return n.post("/api/auth/ingreso", Map.of("usuario", usuario, "clave", clave));
    }

    // ---------- Cerrar la sesión de un usuario ----------

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:47 el administrador cierra la sesión abierta: motivo «cerrada», el usuario recibe 401 «cerrada» y puede volver a entrar")
    void administradorCierraSesion() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        UUID sid = datos.abrirSesion(u.id());
        var suyo = token(u, sid);

        JsonNode fila = null;
        for (JsonNode f : json(leer("/api/usuarios")).path("usuarios")) {
            if (f.path("id").asLong() == u.id()) {
                fila = f;
            }
        }
        assertThat(fila).isNotNull();
        assertThat(fila.path("sesionAbierta").asBoolean()).isTrue();

        publicar("/api/usuarios/" + u.id() + "/cerrar-sesion", null).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Sesión cerrada."));
        assertThat(motivoDeCierre(sid)).isEqualTo("cerrada");
        assertThat(sesionAbierta(u.id())).isFalse();

        leer(suyo, "/api/sesion").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("cerrada"))
                .andExpect(jsonPath("$.mensaje").value("Su sesión fue cerrada. Vuelva a iniciar sesión."));
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"));
        n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(u.correo()))).andExpect(status().isOk());
        assertThat(sesionAbierta(u.id())).as("ya no la bloquea: abre una nueva").isTrue();
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:47 cerrar la sesión de quien no la tiene: «El usuario no tenía una sesión abierta.»; de quien no existe: 404")
    void cerrarSinSesionYInexistente() throws Exception {
        var sinSesion = datos.usuario(Perfil.AUDITOR);
        publicar("/api/usuarios/" + sinSesion.id() + "/cerrar-sesion", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("El usuario no tenía una sesión abierta."));
        publicar("/api/usuarios/999999999/cerrar-sesion", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El usuario no existe."));
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:77 inactivar a un usuario con sesión abierta la cierra con motivo «inactivo» (el usuario ve «Su usuario está inactivo.»)")
    void inactivarCierraLaSesion() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        UUID sid = datos.abrirSesion(u.id());
        poner("/api/usuarios/" + u.id() + "/estado", Map.of("activo", false)).andExpect(status().isOk());
        assertThat(motivoDeCierre(sid)).isEqualTo("inactivo");
        leer(token(u, sid), "/api/sesion").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("inactivo"))
                .andExpect(jsonPath("$.mensaje").value("Su usuario está inactivo."));
    }

    // ---------- Contraseña inicial ----------

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:91 contraseña inicial al crear: el usuario la cambia al ingresar y sigue con el código; la inicial ya no sirve")
    void claveInicialAlCrear() throws Exception {
        String u = unico("inicial");
        Map<String, Object> d = datosUsuario(u, 3);
        d.put("claveInicial", INICIAL);
        publicar("/api/usuarios", d).andExpect(status().isCreated()).andExpect(jsonPath("$.mensaje").value(
                "Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: deberá cambiarla en su primer ingreso."));
        assertThat(correos.a(correoDe(u))).as("sin correo de bienvenida").isEmpty();

        Navegador n = navegador();
        paso1(n, u, INICIAL).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("cambiar_clave"));
        assertThat(mensaje(n.post("/api/auth/cambiar-clave", Map.of("clave", INICIAL, "confirmacion", INICIAL)))).contains("diferente");
        assertThat(mensaje(n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", "Otra.Cosa.2026")))).contains("no coinciden");
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("codigo"));
        n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(correoDe(u)))).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT clave_inicial OR debe_cambiar_clave FROM usuarios WHERE usuario = :u").param("u", u).query(Boolean.class).single()).isFalse();
        assertThat(navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", INICIAL)).andReturn().getResponse().getStatus())
                .as("la inicial ya no sirve").isEqualTo(401);
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:142 asignar contraseña inicial a un usuario existente cierra su sesión (motivo «cerrada») y lo obliga a cambiarla")
    void asignarClaveInicialExistente() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = datos.abrirSesion(u.id());
        publicar("/api/usuarios/" + u.id() + "/clave-inicial", Map.of("clave", INICIAL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar."));
        assertThat(motivoDeCierre(sid)).as("como el FiltroSesion del monolito: la contraseña cambiada después del inicio se revisa primero").isEqualTo("clave");
        assertThat(jdbc.sql("SELECT clave_inicial AND debe_cambiar_clave FROM usuarios WHERE id = :id").param("id", u.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :id").param("id", u.id()).query(String.class).single())
                .startsWith("{bcrypt}$2").doesNotContain(INICIAL);
        leer(token(u, sid), "/api/sesion").andExpect(status().isUnauthorized());
        paso1(navegador(), u.usuario(), INICIAL).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("cambiar_clave"));
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:129 contraseña inicial débil o vacía: 422 «Contraseña inicial: …» y no cambia nada; usuario inexistente: 404; solo el administrador")
    void claveInicialBordes() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        String hash = jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :id").param("id", u.id()).query(String.class).single();
        assertThat(mensaje(publicar("/api/usuarios/" + u.id() + "/clave-inicial", Map.of("clave", "corta")))).startsWith("Contraseña inicial:");
        publicar("/api/usuarios/" + u.id() + "/clave-inicial", Map.of()).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :id").param("id", u.id()).query(String.class).single()).isEqualTo(hash);
        publicar("/api/usuarios/999999999/clave-inicial", Map.of("clave", INICIAL)).andExpect(status().isNotFound());
        publicar(sesionDe(datos.usuario(Perfil.COORDINADOR)), "/api/usuarios/" + u.id() + "/clave-inicial", Map.of("clave", INICIAL))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :id").param("id", u.id()).query(String.class).single()).isEqualTo(hash);
    }

    // ---------- Nombres de perfiles ----------

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:173 el administrador renombra un perfil y la sesión muestra el nombre nuevo; nombre repetido o corto 422; perfil inexistente 404")
    void renombrarPerfil() throws Exception {
        String antes = jdbc.sql("SELECT nombre FROM perfiles WHERE id = 10").query(String.class).single();
        String descripcion = jdbc.sql("SELECT descripcion FROM perfiles WHERE id = 10").query(String.class).single();
        try {
            poner("/api/usuarios/perfiles/10", Map.of("nombre", "Analista de cuentas", "descripcion", "Prueba")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mensaje").value("Perfil actualizado."));
            var u = datos.usuario(Perfil.ANALISTA);
            JsonNode s = json(leer(sesionDe(u), "/api/sesion").andExpect(status().isOk()));
            assertThat(s.path("perfiles").toString()).contains("Analista de cuentas");

            assertThat(mensaje(poner("/api/usuarios/perfiles/10", Map.of("nombre", "administrador")))).isEqualTo("Ya existe otro perfil con ese nombre.");
            assertThat(mensaje(poner("/api/usuarios/perfiles/10", Map.of("nombre", "ab")))).isEqualTo("El nombre del perfil debe tener de 3 a 60 caracteres.");
            assertThat(mensaje(poner("/api/usuarios/perfiles/10", Map.of("nombre", "Analista de cuentas", "descripcion", "d".repeat(201)))))
                    .isEqualTo("La descripción tiene máximo 200 caracteres.");
            poner("/api/usuarios/perfiles/10", Map.of("nombre", "Analista de cuentas")).andExpect(status().isOk());
            poner("/api/usuarios/perfiles/99", Map.of("nombre", "No existe")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.mensaje").value("El perfil no existe."));
            poner(sesionDe(datos.usuario(Perfil.COORDINADOR)), "/api/usuarios/perfiles/10", Map.of("nombre", "Intruso")).andExpect(status().isForbidden());
            assertThat(jdbc.sql("SELECT nombre FROM perfiles WHERE id = 10").query(String.class).single()).isEqualTo("Analista de cuentas");
        } finally {
            jdbc.sql("UPDATE perfiles SET nombre = :n, descripcion = :d WHERE id = 10").param("n", antes).param("d", descripcion).update();
        }
    }

    // ---------- TIME OUT (Usuarios › Seguridad) ----------

    private void restaurarMinutos(int original) throws Exception {
        jdbc.sql("UPDATE seg_parametros SET valor = :v WHERE clave = 'sesion_minutos'").param("v", String.valueOf(original)).update();
        // Por la API para limpiar también la caché de 30 s (el cambio por SQL no la toca)
        poner("/api/usuarios/seguridad", Map.of("minutosSesion", original));
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:127 TIME OUT: el administrador cambia seg_parametros.sesion_minutos y aplica de inmediato (la caché se limpia); 1 y 480 valen")
    void tiempoDeInactividadEditable() throws Exception {
        JsonNode actual = json(leer("/api/usuarios/seguridad").andExpect(status().isOk()).andExpect(jsonPath("$.minutosMaximo").value(480)));
        int original = actual.path("minutosSesion").asInt();
        var u = datos.usuario(Perfil.COORDINADOR);
        var suyo = sesionDe(u);
        try {
            assertThat(json(leer(suyo, "/api/sesion")).path("minutosInactividad").asInt()).as("calienta la caché").isEqualTo(original);

            cambiarMinutos(1);
            assertThat(jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = 'sesion_minutos'").query(String.class).single()).isEqualTo("1");
            leer("/api/usuarios/seguridad").andExpect(jsonPath("$.minutosSesion").value(1));
            assertThat(json(leer(suyo, "/api/sesion")).path("minutosInactividad").asInt()).as("aplica de inmediato, sin esperar 30 s").isEqualTo(1);

            reloj.avanzar(Duration.ofSeconds(61));
            leer(suyo, "/api/sesion").andExpect(status().isOk()); // dentro del margen: la actividad pudo no haberse informado aún
            reloj.avanzar(Duration.ofSeconds(60)); // 1 min + MARGEN_INFORME (60 s) del servidor + 1 s
            leer(suyo, "/api/sesion").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("inactividad"));

            // El administrador siguió activo: el gateway habría informado su actividad
            jdbc.sql("UPDATE sesiones SET ultimo = :a WHERE id = :id").param("a", reloj.instant().atOffset(java.time.ZoneOffset.UTC)).param("id", sidAdmin).update();
            cambiarMinutos(480);
            leer("/api/usuarios/seguridad").andExpect(jsonPath("$.minutosSesion").value(480));
        } finally {
            reloj.reiniciar();
            restaurarMinutos(original);
        }
    }

    private void cambiarMinutos(int minutos) throws Exception {
        poner("/api/usuarios/seguridad", Map.of("minutosSesion", minutos)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Tiempo de inactividad actualizado: " + minutos + " minutos. Aplica desde la siguiente acción de cada usuario."));
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:127 TIME OUT: fuera de 1 a 480 o vacío, 422 con campo minutosSesion y sin cambiar nada; solo el administrador")
    void tiempoDeInactividadValidaciones() throws Exception {
        int original = json(leer("/api/usuarios/seguridad")).path("minutosSesion").asInt();
        try {
            for (int malo : new int[] {0, -1, 481}) {
                poner("/api/usuarios/seguridad", Map.of("minutosSesion", malo)).andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.mensaje").value("Los minutos de inactividad deben ser un número entero de 1 a 480."))
                        .andExpect(jsonPath("$.campo").value("minutosSesion"));
            }
            poner("/api/usuarios/seguridad", Map.of()).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.mensaje").value("Escriba los minutos de inactividad.")).andExpect(jsonPath("$.campo").value("minutosSesion"));
            var coordinador = sesionDe(datos.usuario(Perfil.COORDINADOR));
            poner(coordinador, "/api/usuarios/seguridad", Map.of("minutosSesion", 1)).andExpect(status().isForbidden());
            leer(coordinador, "/api/usuarios/seguridad").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
            leer("/api/usuarios/seguridad").andExpect(jsonPath("$.minutosSesion").value(original));
        } finally {
            restaurarMinutos(original);
        }
    }

    // ---------- Desbloquear ----------

    @Test
    @DisplayName("CuentaYSeguridadApiTest:159 el administrador desbloquea a un usuario bloqueado por intentos y este vuelve a ingresar; solo el administrador; 404 si no existe")
    void desbloquear() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        jdbc.sql("UPDATE usuarios SET intentos = 5, bloqueado_hasta = now() + interval '10 minutes' WHERE id = :id").param("id", u.id()).update();
        assertThat(paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andReturn().getResponse().getStatus()).as("bloqueado no entra").isEqualTo(401);

        publicar(sesionDe(datos.usuario(Perfil.COORDINADOR)), "/api/usuarios/" + u.id() + "/desbloquear", null).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT bloqueado_hasta IS NOT NULL FROM usuarios WHERE id = :id").param("id", u.id()).query(Boolean.class).single()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/usuarios/" + u.id() + "/desbloquear"))
                .andExpect(status().isUnauthorized());

        publicar("/api/usuarios/" + u.id() + "/desbloquear", null).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Usuario desbloqueado."));
        assertThat(jdbc.sql("SELECT bloqueado_hasta IS NULL AND intentos = 0 FROM usuarios WHERE id = :id").param("id", u.id()).query(Boolean.class).single()).isTrue();
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"));

        publicar("/api/usuarios/" + u.id() + "/desbloquear", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("El usuario no estaba bloqueado."));
        publicar("/api/usuarios/999999999/desbloquear", null).andExpect(status().isNotFound());
    }

    // ---------- Diseño S2: la lista refleja el estado ----------

    @Test
    @DisplayName("Contrato listarUsuarios: bloqueado, claveInicial y sesionAbierta de cada fila reflejan la base")
    void listaReflejaElEstado() throws Exception {
        var bloqueado = datos.usuario(Perfil.AUDITOR);
        jdbc.sql("UPDATE usuarios SET intentos = 5, bloqueado_hasta = now() + interval '10 minutes' WHERE id = :id").param("id", bloqueado.id()).update();
        var inicial = datos.nuevo().claveInicial().crear();
        JsonNode lista = json(leer("/api/usuarios").andExpect(status().isOk()));
        boolean vistoBloqueado = false;
        boolean vistoInicial = false;
        for (JsonNode f : lista.path("usuarios")) {
            if (f.path("id").asLong() == bloqueado.id()) {
                vistoBloqueado = true;
                assertThat(f.path("bloqueado").asBoolean()).isTrue();
                assertThat(f.path("sesionAbierta").asBoolean()).isFalse();
            }
            if (f.path("id").asLong() == inicial.id()) {
                vistoInicial = true;
                assertThat(f.path("claveInicial").asBoolean()).isTrue();
            }
        }
        assertThat(vistoBloqueado && vistoInicial).isTrue();
    }
}
