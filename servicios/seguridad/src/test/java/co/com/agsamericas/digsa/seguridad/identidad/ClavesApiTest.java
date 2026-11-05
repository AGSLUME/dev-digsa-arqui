package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.Navegador;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * S1.3: «¿Olvidó su contraseña?», enlace de un solo uso (GET/POST {@code /api/auth/clave}) y «Cambiar mi contraseña»
 * ({@code /api/cuenta/clave}, con token). Portado de {@code UsuariosYClavesApiTest}, {@code IngresoYSesionApiTest} y
 * {@code CuentaYSeguridadApiTest} del monolito. Contrato: {@code contratos/seguridad.yaml}.
 */
class ClavesApiTest extends PruebaIntegracion {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MENSAJE_OLVIDE = "Si el usuario o el correo están registrados y activos, le enviamos un enlace para definir una contraseña nueva.";
    private static final String ENLACE_INVALIDO = "El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».";
    private static final String NUEVA = "Nueva.Clave.2026";

    // ---------- Ayudas ----------

    private void esperar(BooleanSupplier condicion, String queEspera) throws InterruptedException {
        long limite = System.currentTimeMillis() + 3000;
        while (!condicion.getAsBoolean()) {
            if (System.currentTimeMillis() > limite) {
                throw new AssertionError("No ocurrió en 3 s: " + queEspera);
            }
            Thread.sleep(50);
        }
    }

    private void esperarCorreoA(String correo, int cantidad) throws InterruptedException {
        esperar(() -> correos.a(correo).size() >= cantidad, cantidad + " correo(s) a " + correo);
    }

    private ResultActions olvide(Navegador n, String valor) throws Exception {
        return n.post("/api/auth/olvide", Map.of("valor", valor));
    }

    /** Pide el enlace por «olvidé» y devuelve el token del correo. */
    private String pedirEnlace(DatosPrueba.Ficticio u) throws Exception {
        olvide(navegador(), u.usuario()).andExpect(status().isOk());
        esperarCorreoA(u.correo(), 1);
        return correos.ultimoEnlace(u.correo());
    }

    private ResultActions definir(Navegador n, String tkn, String clave, String confirmacion) throws Exception {
        return n.post("/api/auth/clave", Map.of("tkn", tkn, "clave", clave, "confirmacion", confirmacion));
    }

    private long sesionesAbiertas(long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM sesiones WHERE usuario_id = :u AND cerrada IS NULL").param("u", usuarioId)
                .query(Long.class).single();
    }

    /** Una sesión abierta del usuario (como la deja el ingreso completo); devuelve su id (claim sid). */
    private UUID abrirSesion(long usuarioId) {
        UUID sid = UUID.randomUUID();
        var ahora = reloj.instant().atOffset(ZoneOffset.UTC);
        jdbc.sql("INSERT INTO sesiones (id, usuario_id, inicio, ultimo, ip) VALUES (:id, :u, :ahora, :ahora, '203.0.113.10')")
                .param("id", sid).param("u", usuarioId).param("ahora", ahora).update();
        return sid;
    }

    private ResultActions cambiarMiClave(DatosPrueba.Ficticio u, UUID sid, Object cuerpo) throws Exception {
        return mvc.perform(post("/api/cuenta/clave")
                .with(jwt().jwt(j -> j.claim("uid", u.id()).claim("usuario", u.usuario()).claim("sid", sid.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(cuerpo)));
    }

    private Map<String, String> cambio(String actual, String clave, String confirmacion) {
        return Map.of("actual", actual, "clave", clave, "confirmacion", confirmacion);
    }

    // ---------- Olvidé mi contraseña ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:151 olvidé: mismo mensaje exista o no el usuario; el correo llega por usuario o por correo")
    void olvideRespondeLoMismoYEnviaElEnlaceAlUsuarioActivo() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        Navegador tercero = navegador();
        olvide(tercero, u.usuario()).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value(MENSAJE_OLVIDE))
                .andExpect(jsonPath("$.enlaceLocal").doesNotExist());
        olvide(tercero, "nadie@" + DatosPrueba.DOMINIO).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value(MENSAJE_OLVIDE));
        esperarCorreoA(u.correo(), 1);
        assertThat(correos.ultimoEnlace(u.correo())).matches("[a-f0-9]{64}");
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:151 olvidé con el correo en lugar del usuario también envía el enlace")
    void olvidePorCorreoEnviaElEnlace() throws Exception {
        var u = datos.usuario();
        olvide(navegador(), u.correo()).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value(MENSAJE_OLVIDE));
        esperarCorreoA(u.correo(), 1);
    }

    @Test
    @DisplayName("contrato: olvidé con un usuario inactivo responde igual pero no envía correo")
    void olvideConUsuarioInactivoNoEnvia() throws Exception {
        var u = datos.nuevo().inactivo().crear();
        olvide(navegador(), u.usuario()).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value(MENSAJE_OLVIDE));
        Thread.sleep(500);
        assertThat(correos.a(u.correo())).isEmpty();
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:151 olvidé no cierra la sesión abierta del usuario ni exige cambiar la contraseña")
    void olvideNoCierraLaSesionNiCambiaLaClave() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        abrirSesion(u.id());
        olvide(navegador(), u.usuario()).andExpect(status().isOk());
        esperarCorreoA(u.correo(), 1);
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT debe_cambiar_clave FROM usuarios WHERE id = :u").param("u", u.id()).query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("contrato: olvidé sin valor responde 422 «Escriba su usuario o su correo.»")
    void olvideSinValorEs422() throws Exception {
        olvide(navegador(), "   ").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Escriba su usuario o su correo."));
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:168 olvidé dos veces seguidas: un solo enlace cada 5 minutos por usuario")
    void olvideDosVecesSoloEnviaUnEnlaceCadaCincoMinutos() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        Navegador tercero = navegador();
        olvide(tercero, u.usuario());
        esperarCorreoA(u.correo(), 1);
        String primero = correos.ultimoEnlace(u.correo());
        olvide(tercero, u.correo()).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value(MENSAJE_OLVIDE));
        Thread.sleep(500);
        assertThat(correos.a(u.correo())).as("no se envía otro antes de 5 minutos").hasSize(1);
        tercero.get("/api/auth/clave?tkn=" + primero).andExpect(jsonPath("$.valido").value(true));

        reloj.avanzar(Duration.ofMinutes(5).plusSeconds(1));
        olvide(tercero, u.usuario());
        esperarCorreoA(u.correo(), 2);
        assertThat(correos.ultimoEnlace(u.correo())).isNotEqualTo(primero);
    }

    // ---------- GET /api/auth/clave ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:71 GET clave con un enlace vigente: valido=true y el nombre")
    void consultarEnlaceVigenteDevuelveElNombre() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        navegador().get("/api/auth/clave?tkn=" + tkn).andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(true)).andExpect(jsonPath("$.nombre").value(u.nombre()));
    }

    @Test
    @DisplayName("contrato: GET clave con un enlace que no existe responde 200 valido=false con el mensaje del contrato")
    void consultarEnlaceInexistenteEsInvalido() throws Exception {
        navegador().get("/api/auth/clave?tkn=" + "a".repeat(64)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(false)).andExpect(jsonPath("$.mensaje").value(ENLACE_INVALIDO));
    }

    @Test
    @DisplayName("contrato: GET clave con un token mal formado (no hexadecimal) responde 200 valido=false")
    void consultarEnlaceMalFormadoEsInvalido() throws Exception {
        navegador().get("/api/auth/clave?tkn=no-es-hexadecimal").andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(false));
    }

    @Test
    @DisplayName("contrato: el enlace vence a los 60 minutos")
    void elEnlaceVenceALosSesentaMinutos() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        reloj.avanzar(Duration.ofMinutes(59));
        navegador().get("/api/auth/clave?tkn=" + tkn).andExpect(jsonPath("$.valido").value(true));
        reloj.avanzar(Duration.ofMinutes(2));
        navegador().get("/api/auth/clave?tkn=" + tkn).andExpect(jsonPath("$.valido").value(false));
        definir(navegador(), tkn, NUEVA, NUEVA).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(ENLACE_INVALIDO));
    }

    @Test
    @DisplayName("contrato: en la base solo se guarda el SHA-256 del enlace, nunca el token")
    void enLaBaseSoloSeGuardaElHashDelToken() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        String guardado = jdbc.sql("SELECT token_hash FROM usuarios WHERE id = :u").param("u", u.id()).query(String.class).single();
        assertThat(guardado).hasSize(64).isNotEqualTo(tkn)
                .isEqualTo(co.com.agsamericas.digsa.reglas.Huellas.sha256(tkn));
    }

    // ---------- POST /api/auth/clave ----------

    @Test
    @DisplayName("UsuariosYClavesApiTest:65 definir la clave con el enlace: cambia, el enlace no vuelve a servir y se ingresa con la nueva")
    void definirClaveConElEnlaceEsDeUnSoloUso() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        String tkn = pedirEnlace(u);
        Navegador n = navegador();
        definir(n, tkn, NUEVA, NUEVA).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña."));
        n.get("/api/auth/clave?tkn=" + tkn).andExpect(jsonPath("$.valido").value(false));
        definir(n, tkn, "Otra.Clave.2026!", "Otra.Clave.2026!").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(ENLACE_INVALIDO));
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isUnauthorized());
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", NUEVA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("codigo"));
    }

    @Test
    @DisplayName("UsuariosYClavesApiTest:72 definir: la política y la confirmación se validan en el servidor")
    void definirValidaPoliticaYConfirmacion() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        Navegador n = navegador();
        definir(n, tkn, "Corta.1", "Corta.1").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener mínimo 12 caracteres."));
        definir(n, tkn, "Contraseña.2026X", "Contraseña.2026X").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña no puede tener ñ ni tildes."));
        definir(n, tkn, NUEVA, "Otra.Cosa.2026").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Las contraseñas no coinciden."));
        definir(n, tkn, "", "").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Escriba la contraseña."));
        n.get("/api/auth/clave?tkn=" + tkn).andExpect(jsonPath("$.valido").value(true));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            sin mayúsculas      | clave-sin-mayusculas-2026!  | La contraseña debe tener mayúsculas y minúsculas.
            sin minúsculas      | CLAVE-SIN-MINUSCULAS-2026!  | La contraseña debe tener mayúsculas y minúsculas.
            sin número          | Clave-Sin-Numero-Alguno!    | La contraseña debe tener al menos un número.
            sin carácter especial | ClaveSinEspecial2026      | La contraseña debe tener al menos un carácter especial.
            """)
    @DisplayName("contrato: texto de cada regla de la política de claves al definir con enlace")
    void definirRechazaCadaReglaDeLaPolitica(String caso, String clave, String mensaje) throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        definir(navegador(), tkn, clave.strip(), clave.strip()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(mensaje.strip()));
    }

    @Test
    @DisplayName("contrato: más de 64 caracteres responde «La contraseña debe tener máximo 64 caracteres.»")
    void definirRechazaMasDeSesentaYCuatroCaracteres() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        String larga = "Aa1!".repeat(17);
        definir(navegador(), tkn, larga, larga).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener máximo 64 caracteres."));
    }

    @Test
    @DisplayName("contrato: definir con la misma contraseña actual responde «La nueva contraseña debe ser diferente a la actual.»")
    void definirConLaMismaClaveActualEs422() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        definir(navegador(), tkn, DatosPrueba.CLAVE, DatosPrueba.CLAVE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La nueva contraseña debe ser diferente a la actual."));
    }

    @Test
    @DisplayName("contrato: definir con un token inexistente responde 422 «El enlace no es válido o ya venció…»")
    void definirConTokenInexistenteEs422() throws Exception {
        definir(navegador(), "b".repeat(64), NUEVA, NUEVA).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(ENLACE_INVALIDO));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:214 definir cierra la sesión abierta (motivo clave), quita el bloqueo y la marca de clave inicial")
    void definirCierraLaSesionYQuitaBloqueoYClaveInicial() throws Exception {
        var u = datos.nuevo().claveInicial().crear();
        abrirSesion(u.id());
        jdbc.sql("UPDATE usuarios SET intentos = 5, bloqueado_hasta = :h WHERE id = :u")
                .param("h", reloj.instant().plusSeconds(600).atOffset(ZoneOffset.UTC)).param("u", u.id()).update();
        String tkn = pedirEnlace(u);
        definir(navegador(), tkn, NUEVA, NUEVA).andExpect(status().isOk());
        assertThat(sesionesAbiertas(u.id())).isZero();
        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE usuario_id = :u").param("u", u.id()).query(String.class).single()).isEqualTo("clave");
        assertThat(jdbc.sql("SELECT bloqueado_hasta IS NULL AND intentos = 0 AND NOT clave_inicial AND NOT debe_cambiar_clave AND token_hash IS NULL "
                + "FROM usuarios WHERE id = :u").param("u", u.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT clave_vence > :ahora FROM usuarios WHERE id = :u").param("ahora", reloj.instant().atOffset(ZoneOffset.UTC))
                .param("u", u.id()).query(Boolean.class).single()).as("la nueva vence a los 30 días").isTrue();
    }

    @Test
    @DisplayName("contrato: el enlace de un usuario que quedó inactivo ya no sirve")
    void elEnlaceDeUnUsuarioInactivadoNoSirve() throws Exception {
        var u = datos.usuario();
        String tkn = pedirEnlace(u);
        jdbc.sql("UPDATE usuarios SET activo = false WHERE id = :u").param("u", u.id()).update();
        navegador().get("/api/auth/clave?tkn=" + tkn).andExpect(jsonPath("$.valido").value(false));
        definir(navegador(), tkn, NUEVA, NUEVA).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value(ENLACE_INVALIDO));
    }

    // ---------- POST /api/cuenta/clave (con token) ----------

    @Test
    @DisplayName("CuentaYSeguridadApiTest:368 cambiar mi contraseña: la sesión sigue abierta y el próximo ingreso es con la nueva")
    void cambiarMiClaveCorrectoMantieneLaSesionYAplicaLaNueva() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = abrirSesion(u.id());
        jdbc.sql("UPDATE usuarios SET intentos = 2 WHERE id = :u").param("u", u.id()).update();
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, NUEVA, NUEVA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Su contraseña se cambió. La próxima vez ingrese con la contraseña nueva."));
        assertThat(sesionesAbiertas(u.id())).as("la sesión en curso no se cierra").isEqualTo(1);
        assertThat(jdbc.sql("SELECT intentos FROM usuarios WHERE id = :u").param("u", u.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT clave_vence > :limite FROM usuarios WHERE id = :u")
                .param("limite", reloj.instant().plus(Duration.ofDays(29)).atOffset(ZoneOffset.UTC)).param("u", u.id())
                .query(Boolean.class).single()).isTrue();

        jdbc.sql("UPDATE sesiones SET cerrada = :ahora, motivo = 'salida' WHERE id = :s")
                .param("ahora", reloj.instant().atOffset(ZoneOffset.UTC)).param("s", sid).update();
        Navegador n = navegador();
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isUnauthorized());
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", NUEVA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:373 cambiar mi contraseña con la actual errada: 422 «La contraseña actual no es correcta.» y no cambia nada")
    void cambiarMiClaveConActualErradaEs422() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = abrirSesion(u.id());
        String hash = jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :u").param("u", u.id()).query(String.class).single();
        cambiarMiClave(u, sid, cambio("No.Es.La.Mia.2026", NUEVA, NUEVA)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña actual no es correcta."));
        assertThat(jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :u").param("u", u.id()).query(String.class).single()).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT intentos FROM usuarios WHERE id = :u").param("u", u.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:377 cambiar mi contraseña valida política, confirmación, distinta de la actual y campos obligatorios")
    void cambiarMiClaveValidaEnElServidor() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = abrirSesion(u.id());
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, "corta", "corta")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener mínimo 12 caracteres."));
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, DatosPrueba.CLAVE, DatosPrueba.CLAVE)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La nueva contraseña debe ser diferente a la actual."));
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, NUEVA, "Otra.Cosa.2026")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Las contraseñas no coinciden."));
        cambiarMiClave(u, sid, cambio("", NUEVA, NUEVA)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Escriba su contraseña actual."));
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, "", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("Escriba la contraseña nueva."));
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:395 la actual errada 5 veces bloquea al usuario: 401 bloqueado y la sesión queda cerrada con motivo bloqueado")
    void cincoActualesErradasBloqueanYCierranLaSesion() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = abrirSesion(u.id());
        ResultActions ultima = null;
        for (int i = 0; i < 5; i++) {
            ultima = cambiarMiClave(u, sid, cambio("Errada.Numero." + i + "X", NUEVA, NUEVA));
        }
        ultima.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("bloqueado"))
                .andExpect(jsonPath("$.mensaje").value("Su usuario está bloqueado 15 minutos por intentos fallidos. Vuelva a ingresar más tarde."));
        assertThat(jdbc.sql("SELECT bloqueado_hasta > :ahora FROM usuarios WHERE id = :u").param("ahora", reloj.instant().atOffset(ZoneOffset.UTC))
                .param("u", u.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE id = :s AND cerrada IS NOT NULL").param("s", sid).query(String.class).single())
                .isEqualTo("bloqueado");
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:527 cambiar mi contraseña con el usuario ya bloqueado: 401 bloqueado y no cambia nada")
    void cambiarMiClaveConUsuarioYaBloqueadoEs401() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = abrirSesion(u.id());
        String hash = jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :u").param("u", u.id()).query(String.class).single();
        jdbc.sql("UPDATE usuarios SET bloqueado_hasta = :h WHERE id = :u")
                .param("h", reloj.instant().plusSeconds(600).atOffset(ZoneOffset.UTC)).param("u", u.id()).update();
        cambiarMiClave(u, sid, cambio(DatosPrueba.CLAVE, NUEVA, NUEVA)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.motivo").value("bloqueado"));
        assertThat(jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :u").param("u", u.id()).query(String.class).single()).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE id = :s").param("s", sid).query(String.class).single()).isEqualTo("bloqueado");
    }

    @Test
    @DisplayName("contrato: cambiar mi contraseña sin token responde 401")
    void cambiarMiClaveSinTokenEs401() throws Exception {
        mvc.perform(post("/api/cuenta/clave").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(cambio(DatosPrueba.CLAVE, NUEVA, NUEVA)))).andExpect(status().isUnauthorized());
    }
}
