package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * S2.1: administración de usuarios ({@code /api/usuarios}, solo Administrador). Portado de {@code UsuariosYClavesApiTest} del monolito
 * (la parte de administración; olvidé/enlace/definir ya están en {@link ClavesApiTest}). Contrato: {@code contratos/seguridad.yaml} 0.4.0.
 * Sin bitácora (S3) ni eventos de mensajería.
 */
class UsuariosApiTest extends AdministracionApiBase {

    private static final String BIENVENIDA = "Usuario creado. Se le envió el enlace para definir su contraseña.";

    private String claveHash(String usuario) {
        return jdbc.sql("SELECT clave_hash FROM usuarios WHERE usuario = :u").param("u", usuario).query(String.class).single();
    }

    private boolean tieneEnlace(long id) {
        return jdbc.sql("SELECT token_hash IS NOT NULL FROM usuarios WHERE id = :id").param("id", id).query(Boolean.class).single();
    }

    // ---------- Quién puede ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:122 solo el administrador: el Coordinador con sesión recibe 403 «No tiene permiso para esta acción.» y no se crea nada")
    void soloAdministrador() throws Exception {
        var coordinador = datos.usuario(Perfil.COORDINADOR);
        var quien = sesionDe(coordinador);
        leer(quien, "/api/usuarios").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        String u = unico("noadm");
        publicar(quien, "/api/usuarios", datosUsuario(u, 3)).andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isZero();
        poner(quien, "/api/usuarios/" + admin.id() + "/estado", Map.of("activo", false)).andExpect(status().isForbidden());
        publicar(quien, "/api/usuarios/" + admin.id() + "/restablecer", null).andExpect(status().isForbidden());
        publicar(quien, "/api/usuarios/" + admin.id() + "/cerrar-sesion", null).andExpect(status().isForbidden());
        assertThat(contar("SELECT count(*) FROM usuarios WHERE id = ? AND activo", admin.id())).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM sesiones WHERE usuario_id = ? AND cerrada IS NULL", admin.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Diseño S2: sin token no hay acceso (401)")
    void sinTokenEs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/usuarios")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Diseño S2: los permisos se revisan contra la base, no contra el token; si al administrador se le quita el perfil, la siguiente petición es 403")
    void permisosContraLaBase() throws Exception {
        leer("/api/usuarios").andExpect(status().isOk());
        jdbc.sql("DELETE FROM usuarios_perfiles WHERE usuario_id = :u").param("u", admin.id()).update();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", admin.id()).param("p", Perfil.COORDINADOR.id()).update();
        leer("/api/usuarios").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        publicar("/api/usuarios", datosUsuario(unico("tras"), 3)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Diseño S2: la sesión también se revisa en la base; con la sesión del administrador cerrada, 401 «cerrada»")
    void sesionCerradaDelAdministrador() throws Exception {
        jdbc.sql("UPDATE sesiones SET cerrada = now(), motivo = 'cerrada' WHERE id = :id").param("id", sidAdmin).update();
        leer("/api/usuarios").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("cerrada"));
    }

    // ---------- Listado ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:222 perfiles disponibles: los activos más Analista, sin Consulta; con las cinco listas del formulario")
    void listaYOpciones() throws Exception {
        var otro = datos.usuario(Perfil.AUDITOR);
        datos.abrirSesion(otro.id());
        long punto = datos.puntoEsm("ESM de prueba lista", "EJC", true);
        long puntoInactivo = datos.puntoEsm("ESM inactivo lista", "ARC", false);
        long regional = datos.regional("Regional de prueba lista", true);
        long regionalInactiva = datos.regional("Regional inactiva lista", false);

        JsonNode lista = json(leer("/api/usuarios").andExpect(status().isOk()));
        List<String> perfiles = new ArrayList<>();
        lista.path("perfiles").forEach(p -> perfiles.add(p.path("nombre").asString()));
        assertThat(perfiles).contains("Administrador", "Coordinador", "Auditor", "DIGSA", "Sanidad", "ESM", "OPL nacional", "OPL regional", "Analista")
                .doesNotContain("Consulta");
        List<String> fuerzas = new ArrayList<>();
        lista.path("fuerzas").forEach(f -> fuerzas.add(f.path("codigo").asString()));
        assertThat(fuerzas).containsExactlyInAnyOrder("EJC", "ARC", "FAC");

        List<Long> puntos = new ArrayList<>();
        lista.path("puntos").forEach(p -> puntos.add(p.path("id").asLong()));
        assertThat(puntos).contains(punto).doesNotContain(puntoInactivo);
        List<Long> regionales = new ArrayList<>();
        lista.path("regionales").forEach(r -> regionales.add(r.path("id").asLong()));
        assertThat(regionales).contains(regional).doesNotContain(regionalInactiva);

        JsonNode fila = null;
        for (JsonNode f : lista.path("usuarios")) {
            if (f.path("id").asLong() == otro.id()) {
                fila = f;
            }
        }
        assertThat(fila).as("el usuario recién creado aparece en la lista").isNotNull();
        assertThat(fila.path("usuario").asString()).isEqualTo(otro.usuario());
        assertThat(fila.path("correo").asString()).isEqualTo(otro.correo());
        assertThat(fila.path("activo").asBoolean()).isTrue();
        assertThat(fila.path("bloqueado").asBoolean()).isFalse();
        assertThat(fila.path("sesionAbierta").asBoolean()).isTrue();
        assertThat(fila.path("claveInicial").asBoolean()).isFalse();
        assertThat(fila.path("perfiles").toString()).isEqualTo("[3]");
        assertThat(fila.has("claveHash")).as("nunca se expone la contraseña").isFalse();
        assertThat(fila.toString()).doesNotContain("$2");
    }

    // ---------- Crear ----------

    @ParameterizedTest(name = "perfil {0}")
    @CsvSource({"10,Analista", "3,Auditor", "1,Administrador", "8,OPL nacional"})
    @DisplayName("UsuariosYClavesApiTest:46 prueba 2: el administrador crea Analista, Auditor, Administrador y OPL; la contraseña queda cifrada y llega el enlace «Bienvenido a la Suite DIGSA»")
    void crearLosPerfilesDeLaPrueba(int perfil, String nombre) throws Exception {
        String u = unico(nombre.replace(' ', '-'));
        publicar("/api/usuarios", datosUsuario(u, perfil))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creado").value(true))
                .andExpect(jsonPath("$.correoOk").value(true))
                .andExpect(jsonPath("$.mensaje").value(BIENVENIDA))
                .andExpect(jsonPath("$.enlaceLocal").doesNotExist());
        String hash = claveHash(u);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(DatosPrueba.CLAVE);
        assertThat(jdbc.sql("SELECT clave_definida FROM usuarios WHERE usuario = :u").param("u", u).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT perfil_id FROM usuarios_perfiles up JOIN usuarios x ON x.id = up.usuario_id WHERE x.usuario = :u")
                .param("u", u).query(Integer.class).list()).containsExactly(perfil);
        var enviados = correos.a(correoDe(u));
        assertThat(enviados).hasSize(1);
        assertThat(enviados.getFirst().asunto()).isEqualTo("Bienvenido a la Suite DIGSA");
        assertThat(correos.ultimoEnlace(correoDe(u))).matches("[a-f0-9]{64}");
        assertThat(jdbc.sql("SELECT token_hash FROM usuarios WHERE usuario = :u").param("u", u).query(String.class).single())
                .as("en la base solo el SHA-256 del enlace").isNotEqualTo(correos.ultimoEnlace(correoDe(u))).hasSize(64);
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:203 correo que no sale: crear responde 201 con correoOk=false y el texto del monolito; el usuario queda creado")
    void crearConCorreoQueNoSale() throws Exception {
        String u = unico("rechazo");
        correos.fallarSiguiente();
        ResultActions r = publicar("/api/usuarios", datosUsuario(u, 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correoOk").value(false))
                .andExpect(jsonPath("$.creado").value(true));
        String texto = mensaje(r);
        assertThat(texto).startsWith("Usuario creado, pero NO se pudo enviar el correo (")
                .endsWith("). Use «Restablecer contraseña» cuando el correo funcione.");
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isEqualTo(1);
        assertThat(correos.a(correoDe(u))).isEmpty();
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:92 con contraseña inicial: 201 sin correo de bienvenida y con el texto del monolito")
    void crearConClaveInicialNoEnviaCorreo() throws Exception {
        String u = unico("inicial");
        Map<String, Object> d = datosUsuario(u, 3);
        d.put("claveInicial", "Inicial.Asignada.2026");
        publicar("/api/usuarios", d)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correoOk").value(true))
                .andExpect(jsonPath("$.mensaje").value("Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: deberá cambiarla en su primer ingreso."));
        assertThat(correos.a(correoDe(u))).as("sin correo de bienvenida").isEmpty();
        assertThat(jdbc.sql("SELECT clave_inicial AND debe_cambiar_clave FROM usuarios WHERE usuario = :u").param("u", u).query(Boolean.class).single()).isTrue();
        assertThat(claveHash(u)).startsWith("{bcrypt}$2").doesNotContain("Inicial.Asignada.2026");
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:129 una contraseña inicial débil se rechaza con «Contraseña inicial: …» y no se crea el usuario")
    void claveInicialDebil() throws Exception {
        String u = unico("debil");
        Map<String, Object> d = datosUsuario(u, 3);
        d.put("claveInicial", "corta");
        publicar("/api/usuarios", d).andExpect(status().isUnprocessableEntity());
        assertThat(mensaje(publicar("/api/usuarios", d))).startsWith("Contraseña inicial:").contains("12 caracteres");
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isZero();
    }

    // ---------- Validaciones ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:84 duplicados: usuario o correo repetido, con los textos del monolito")
    void duplicados() throws Exception {
        String u = unico("dup");
        crearUsuarioPorApi(datosUsuario(u, 2));
        publicar("/api/usuarios", datosUsuario(u, 2)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un usuario con ese nombre de usuario."));
        Map<String, Object> mismoCorreo = datosUsuario(unico("otro"), 2);
        mismoCorreo.put("correo", correoDe(u));
        publicar("/api/usuarios", mismoCorreo).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un usuario con ese correo."));
        Map<String, Object> correoEnMayusculas = datosUsuario(unico("mayus"), 2);
        correoEnMayusculas.put("correo", correoDe(u).toUpperCase());
        assertThat(mensaje(publicar("/api/usuarios", correoEnMayusculas))).as("el correo se normaliza a minúsculas")
                .isEqualTo("Ya existe un usuario con ese correo.");
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:84 combinaciones de perfiles, alcance faltante, documento del Auditor y perfil inactivo")
    void combinacionesYAlcances() throws Exception {
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("comb"), 5, 6)))).contains("un solo perfil");
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("oplaud"), 9, 3)))).contains("no se combinan");
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("san"), 6)))).contains("necesita la fuerza");
        Map<String, Object> sinDoc = datosUsuario(unico("sindoc"), 3);
        sinDoc.remove("documento");
        assertThat(mensaje(publicar("/api/usuarios", sinDoc))).contains("documento es obligatorio");
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("cons"), 4)))).as("Consulta está inactivo").isEqualTo("Hay un perfil no válido.");
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("noexiste"), 77)))).isEqualTo("Hay un perfil no válido.");
        Map<String, Object> sanidad = datosUsuario(unico("sanok"), 6);
        sanidad.put("alcanceFuerza", "EJC");
        publicar("/api/usuarios", sanidad).andExpect(status().isCreated());
        Map<String, Object> fuerzaRara = datosUsuario(unico("sanmal"), 6);
        fuerzaRara.put("alcanceFuerza", "XYZ");
        assertThat(mensaje(publicar("/api/usuarios", fuerzaRara))).contains("necesita la fuerza");
    }

    @Test
    @DisplayName("Revalidación en el servidor: nombre, usuario, correo, perfiles, documento y teléfono inválidos responden 422 con el texto del monolito")
    void camposInvalidos() throws Exception {
        Map<String, Object> d = datosUsuario("Con Espacios", 2);
        publicar("/api/usuarios", d).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo."));
        d = datosUsuario(unico("n"), 2);
        d.put("nombre", "ab");
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("Escriba el nombre completo (mínimo 3 caracteres).");
        d = datosUsuario(unico("c"), 2);
        d.put("correo", "no-es-un-correo");
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("Escriba un correo válido.");
        d = datosUsuario(unico("d"), 2);
        d.put("documento", "9".repeat(31));
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("El documento y el teléfono tienen máximo 30 caracteres.");
        d = datosUsuario(unico("t"), 2);
        d.put("telefono", "12345");
        assertThat(mensaje(publicar("/api/usuarios", d))).startsWith("El teléfono debe ser un móvil colombiano");
        d = datosUsuario(unico("p"), 2);
        d.put("perfiles", List.of());
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("Marque al menos un perfil.");
        d.put("perfiles", null);
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("Marque al menos un perfil.");
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario LIKE 'con espacios%' OR nombre = 'ab'")).isZero();
    }

    @ParameterizedTest(name = "usuario [{0}]")
    @ValueSource(strings = {"ab", "x", "con tilde á", "emoji😀user", "x'; DROP TABLE usuarios;--", "con espacio", "ñandú"})
    @DisplayName("Caracteres especiales y límites del usuario: 422 y la tabla de usuarios intacta")
    void usuariosNoValidos(String usuario) throws Exception {
        long antes = contar("SELECT count(*) FROM usuarios");
        Map<String, Object> d = datosUsuario(usuario, 2);
        d.put("correo", correoDe(unico("ok")));
        publicar("/api/usuarios", d).andExpect(status().isUnprocessableEntity());
        assertThat(contar("SELECT count(*) FROM usuarios")).isEqualTo(antes);
    }

    @Test
    @DisplayName("ServicioUsuarios.guardar: el usuario se pasa a minúsculas antes de validarlo y de guardarlo")
    void usuarioEnMayusculasSeGuardaEnMinusculas() throws Exception {
        String u = unico("mayus");
        Map<String, Object> d = datosUsuario(u, 2);
        d.put("usuario", u.toUpperCase());
        d.put("correo", correoDe(unico("mc")));
        publicar("/api/usuarios", d).andExpect(status().isCreated());
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isEqualTo(1);
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:108 prueba 2 en dos equipos a la vez: el mismo usuario solo se crea una vez; el otro recibe 422, no 500")
    void simultaneoEnDosEquipos() throws Exception {
        String u = unico("doble");
        Map<String, Object> d = datosUsuario(u, 3);
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> estadoDe(() -> publicar("/api/usuarios", d)));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> estadoDe(() -> publicar("/api/usuarios", d)));
        assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 422);
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", u)).isEqualTo(1);
        assertThat(correos.a(correoDe(u))).as("un solo correo de bienvenida").hasSize(1);
    }

    @FunctionalInterface
    private interface Peticion {
        ResultActions hacer() throws Exception;
    }

    private static int estadoDe(Peticion p) {
        try {
            return p.hacer().andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- ESM y OPL regional (copia local de visitas; falla cerrado) ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:222 ESM sin establecimiento, con uno inexistente o inactivo: 422; con uno activo: 201 y queda en alcance_punto")
    void esmExigeEstablecimientoActivo() throws Exception {
        String esperado = "El perfil ESM necesita el establecimiento (ESM activo).";
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("esm"), 7)))).isEqualTo(esperado);
        Map<String, Object> inexistente = datosUsuario(unico("esmno"), 7);
        inexistente.put("alcancePunto", 999_999_999L);
        assertThat(mensaje(publicar("/api/usuarios", inexistente))).as("falla cerrado: no está en la copia").isEqualTo(esperado);
        Map<String, Object> inactivo = datosUsuario(unico("esmin"), 7);
        inactivo.put("alcancePunto", datos.puntoEsm("ESM inactivo", "EJC", false));
        assertThat(mensaje(publicar("/api/usuarios", inactivo))).isEqualTo(esperado);

        long punto = datos.puntoEsm("ESM activo", "ARC", true);
        String u = unico("esmok");
        Map<String, Object> ok = datosUsuario(u, 7);
        ok.put("alcancePunto", punto);
        publicar("/api/usuarios", ok).andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT alcance_punto FROM usuarios WHERE usuario = :u").param("u", u).query(Long.class).single()).isEqualTo(punto);
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:222 OPL regional sin regional, con una inexistente o inactiva: 422; con una activa: 201 y queda en alcance_regional")
    void oplRegionalExigeRegionalActiva() throws Exception {
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("oplr"), 9)))).contains("necesita la regional");
        Map<String, Object> inexistente = datosUsuario(unico("oplrno"), 9);
        inexistente.put("alcanceRegional", 999_999_999L);
        assertThat(mensaje(publicar("/api/usuarios", inexistente))).as("falla cerrado: no está en la copia").contains("necesita la regional");
        Map<String, Object> inactiva = datosUsuario(unico("oplrin"), 9);
        inactiva.put("alcanceRegional", datos.regional("Regional inactiva", false));
        assertThat(mensaje(publicar("/api/usuarios", inactiva))).contains("necesita la regional");

        long regional = datos.regional("Regional activa", true);
        String u = unico("oplrok");
        Map<String, Object> ok = datosUsuario(u, 9);
        ok.put("alcanceRegional", regional);
        ResultActions r = publicar("/api/usuarios", ok).andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT alcance_regional FROM usuarios WHERE id = :id").param("id", json(r).path("id").asLong()).query(Long.class).single())
                .isEqualTo(regional);
    }

    @Test
    @DisplayName("El alcance solo se guarda si el perfil lo usa: un Auditor con alcancePunto lo descarta")
    void alcanceNoUsadoSeDescarta() throws Exception {
        String u = unico("sinalc");
        Map<String, Object> d = datosUsuario(u, 3);
        d.put("alcancePunto", datos.puntoEsm("ESM ajeno", "EJC", true));
        d.put("alcanceRegional", datos.regional("Regional ajena", true));
        d.put("alcanceFuerza", "EJC");
        publicar("/api/usuarios", d).andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT alcance_punto IS NULL AND alcance_regional IS NULL AND alcance_fuerza IS NULL FROM usuarios WHERE usuario = :u")
                .param("u", u).query(Boolean.class).single()).isTrue();
    }

    // ---------- Editar ----------

    @Test
    @DisplayName("Contrato editarUsuario: «Usuario actualizado.»; cambia perfiles y datos; no cambia la contraseña ni crea correo")
    void editar() throws Exception {
        String u = unico("edit");
        long id = crearUsuarioPorApi(datosUsuario(u, 3));
        correos.limpiar();
        String hash = claveHash(u);
        Map<String, Object> d = datosUsuario(u, 2);
        d.put("nombre", "Nombre Cambiado De Prueba");
        d.put("telefono", "3001234567");
        poner("/api/usuarios/" + id, d).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("Usuario actualizado."))
                .andExpect(jsonPath("$.creado").value(false)).andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.sql("SELECT nombre FROM usuarios WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("Nombre Cambiado De Prueba");
        assertThat(jdbc.sql("SELECT perfil_id FROM usuarios_perfiles WHERE usuario_id = :id").param("id", id).query(Integer.class).list()).containsExactly(2);
        assertThat(claveHash(u)).isEqualTo(hash);
        assertThat(correos.enviados()).isEmpty();
    }

    @Test
    @DisplayName("Contrato editarUsuario: si cambia el correo, el enlace pendiente deja de servir; el propio usuario y correo no cuentan como duplicado")
    void editarCambiaCorreoAnulaEnlace() throws Exception {
        String u = unico("edcorreo");
        long id = crearUsuarioPorApi(datosUsuario(u, 3));
        assertThat(tieneEnlace(id)).isTrue();
        poner("/api/usuarios/" + id, datosUsuario(u, 3)).andExpect(status().isOk());
        assertThat(tieneEnlace(id)).as("mismo correo: el enlace sigue").isTrue();
        Map<String, Object> d = datosUsuario(u, 3);
        d.put("correo", correoDe(unico("nuevo")));
        poner("/api/usuarios/" + id, d).andExpect(status().isOk());
        assertThat(tieneEnlace(id)).isFalse();
        Map<String, Object> repetido = datosUsuario(u, 3);
        repetido.put("correo", admin.correo());
        assertThat(mensaje(poner("/api/usuarios/" + id, repetido))).isEqualTo("Ya existe un usuario con ese correo.");
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:131 el administrador no puede quitarse a sí mismo el perfil Administrador; editar a uno que no existe es 404")
    void editarSinQuitarseAdministrador() throws Exception {
        Map<String, Object> d = datosUsuario(admin.usuario(), 2);
        d.put("correo", admin.correo());
        poner("/api/usuarios/" + admin.id(), d).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("No puede quitarse a sí mismo el perfil Administrador."));
        assertThat(jdbc.sql("SELECT perfil_id FROM usuarios_perfiles WHERE usuario_id = :id").param("id", admin.id()).query(Integer.class).list())
                .containsExactly(1);
        poner("/api/usuarios/999999999", datosUsuario(unico("nadie"), 2)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El usuario no existe."));
    }

    // ---------- Estado, restablecer ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:131 no puede inactivarse a sí mismo; inactivar y activar a otro con los textos del monolito")
    void estado() throws Exception {
        poner("/api/usuarios/" + admin.id() + "/estado", Map.of("activo", false)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("No puede inactivar su propio usuario."));
        assertThat(jdbc.sql("SELECT activo FROM usuarios WHERE id = :id").param("id", admin.id()).query(Boolean.class).single()).isTrue();
        var otro = datos.usuario(Perfil.AUDITOR);
        poner("/api/usuarios/" + otro.id() + "/estado", Map.of("activo", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Usuario inactivado."));
        assertThat(jdbc.sql("SELECT activo FROM usuarios WHERE id = :id").param("id", otro.id()).query(Boolean.class).single()).isFalse();
        poner("/api/usuarios/" + otro.id() + "/estado", Map.of("activo", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Usuario activado."));
        assertThat(jdbc.sql("SELECT activo FROM usuarios WHERE id = :id").param("id", otro.id()).query(Boolean.class).single()).isTrue();
        poner("/api/usuarios/999999999/estado", Map.of("activo", false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El usuario no existe."));
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:131 restablecer exige el cambio de contraseña y envía el enlace al correo")
    void restablecer() throws Exception {
        var otro = datos.usuario(Perfil.AUDITOR);
        publicar("/api/usuarios/" + otro.id() + "/restablecer", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Se envió al correo del usuario el enlace para definir una contraseña nueva."));
        assertThat(jdbc.sql("SELECT debe_cambiar_clave FROM usuarios WHERE id = :id").param("id", otro.id()).query(Boolean.class).single()).isTrue();
        assertThat(tieneEnlace(otro.id())).isTrue();
        assertThat(correos.ultimoEnlace(otro.correo())).matches("[a-f0-9]{64}");
        publicar("/api/usuarios/999999999/restablecer", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El usuario no existe."));
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:203 restablecer con el correo caído responde 502 «No se pudo enviar el correo…»")
    void restablecerConCorreoCaido() throws Exception {
        var otro = datos.usuario(Perfil.AUDITOR);
        correos.fallarSiguiente();
        ResultActions r = publicar("/api/usuarios/" + otro.id() + "/restablecer", null).andExpect(status().isBadGateway());
        assertThat(mensaje(r)).startsWith("No se pudo enviar el correo");
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:245 inactivar cierra la sesión abierta del otro (motivo «inactivo») y anula su enlace pendiente")
    void inactivarCierraSesionYAnulaEnlace() throws Exception {
        var otro = datos.usuario(Perfil.COORDINADOR);
        UUID sid = datos.abrirSesion(otro.id());
        publicar("/api/usuarios/" + otro.id() + "/restablecer", null).andExpect(status().isOk());
        assertThat(tieneEnlace(otro.id())).isTrue();

        poner("/api/usuarios/" + otro.id() + "/estado", Map.of("activo", false)).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE id = :id AND cerrada IS NOT NULL").param("id", sid).query(String.class).single())
                .isEqualTo("inactivo");
        assertThat(tieneEnlace(otro.id())).isFalse();
        leer(token(otro, sid), "/api/sesion").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("inactivo"))
                .andExpect(jsonPath("$.mensaje").value("Su usuario está inactivo."));
    }
}
