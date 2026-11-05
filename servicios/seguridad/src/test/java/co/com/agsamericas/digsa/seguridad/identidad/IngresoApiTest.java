package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.Navegador;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S1.2: ingreso en dos pasos, segundo factor, bloqueo, sesión única y cambio de clave inicial/vencida dentro del ingreso.
 * Portado de {@code IngresoYSesionApiTest}, {@code CuentaYSeguridadApiTest} y {@code SesionesYClaveInicialApiTest} del monolito
 * (digsa-java, identidad). Contrato: {@code contratos/seguridad.yaml}. TDD: el servicio aún no implementa estas rutas.
 */
class IngresoApiTest extends PruebaIntegracion {

    @org.springframework.beans.factory.annotation.Autowired
    ParametrosSeguridad parametros;

    private static final String AUTORIZAR = "/oauth2/authorize?response_type=code&client_id=gateway&scope=openid"
            + "&redirect_uri=http://localhost:9000/bff/login/oauth2/code/lume"
            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256";
    private static final String GENERICO = "Usuario o contraseña incorrectos. Después de 5 intentos fallidos el usuario se bloquea 15 minutos.";
    private static final String SESION_ABIERTA = "Su usuario ya tiene una sesión abierta en otro equipo o navegador. Ciérrela con «Salir», "
            + "espere 10 minutos sin actividad o pida al administrador que la cierre. Solo se permite una sesión a la vez.";
    private static final String NUEVA = "Cambiada.Por.Mi.2026";

    // ---------- Ayudas ----------

    private ResultActions paso1(Navegador n, String usuario, String clave) throws Exception {
        return n.post("/api/auth/ingreso", Map.of("usuario", usuario, "clave", clave));
    }

    private ResultActions codigo(Navegador n, String codigo) throws Exception {
        return n.post("/api/auth/codigo", Map.of("codigo", codigo));
    }

    /** Ingreso completo (paso 1 y 2) con el navegador dado. */
    private ResultActions ingresar(Navegador n, DatosPrueba.Ficticio u) throws Exception {
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        return codigo(n, correos.ultimoCodigo(u.correo()));
    }

    private long sesionesAbiertas(long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM sesiones WHERE usuario_id = :u AND cerrada IS NULL").param("u", usuarioId)
                .query(Long.class).single();
    }

    private int intentos(long usuarioId) {
        return jdbc.sql("SELECT intentos FROM usuarios WHERE id = :u").param("u", usuarioId).query(Integer.class).single();
    }

    private void generico401(ResultActions r) throws Exception {
        r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.mensaje").value(GENERICO)).andExpect(jsonPath("$.motivo").doesNotExist());
    }

    private static String ubicacion(org.springframework.test.web.servlet.MvcResult r) {
        return r.getResponse().getHeader("Location");
    }

    // ---------- Paso 1 ----------

    @Test
    @DisplayName("IngresoYSesionApiTest:19 paso 1 correcto: responde paso codigo y envía el código de 6 dígitos; aún no hay sesión")
    void paso1CorrectoEnviaCodigoPorCorreo() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("codigo"))
                .andExpect(jsonPath("$.mensaje").value("Le enviamos un código de 6 dígitos a su correo."))
                .andExpect(jsonPath("$.enlaceLocal").doesNotExist());
        assertThat(correos.a(u.correo())).hasSize(1);
        assertThat(correos.ultimoCodigo(u.correo())).matches("\\d{6}");
        assertThat(sesionesAbiertas(u.id())).as("sin el código todavía no hay sesión").isZero();
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:26 el correo del paso 1 va enmascarado: no es el correo completo y conserva el dominio")
    void paso1EnmascaraElCorreo() throws Exception {
        var u = datos.usuario();
        var r = paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        String correo = Navegador.campo(r, "correo");
        assertThat(correo).contains("***").endsWith("@" + DatosPrueba.DOMINIO).isNotEqualTo(u.correo());
    }

    @Test
    @DisplayName("contrato: el usuario se normaliza a minúsculas en el paso 1")
    void paso1NormalizaElUsuarioAMinusculas() throws Exception {
        var u = datos.nuevo().usuario("mayus" + System.nanoTime() % 100000).crear();
        paso1(navegador(), u.usuario().toUpperCase(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("codigo"));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:42 mismo mensaje (sin motivo) para usuario inexistente y contraseña errada")
    void mensajeGenericoParaInexistenteYClaveErrada() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        generico401(paso1(navegador(), u.usuario(), "Otra.Clave.2026"));
        generico401(paso1(navegador(), "nadie-" + u.usuario(), DatosPrueba.CLAVE));
        assertThat(correos.a(u.correo())).isEmpty();
    }

    @Test
    @DisplayName("contrato: usuario inactivo responde igual que una contraseña errada y no envía código")
    void usuarioInactivoResponde401Generico() throws Exception {
        var u = datos.nuevo().inactivo().crear();
        generico401(paso1(navegador(), u.usuario(), DatosPrueba.CLAVE));
        assertThat(correos.a(u.correo())).isEmpty();
    }

    @Test
    @DisplayName("contrato: clave inicial vencida (72 h) responde igual que una contraseña errada")
    void claveInicialVencidaResponde401Generico() throws Exception {
        var u = datos.nuevo().claveInicial().crear();
        reloj.avanzar(Duration.ofHours(73));
        generico401(paso1(navegador(), u.usuario(), DatosPrueba.CLAVE));
    }

    @Test
    @DisplayName("contrato: usuario vacío 422 «Escriba su usuario.» y contraseña vacía 422 «Escriba su contraseña.»")
    void paso1ValidaCamposObligatorios() throws Exception {
        var u = datos.usuario();
        navegador().post("/api/auth/ingreso", Map.of("usuario", "", "clave", DatosPrueba.CLAVE))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Escriba su usuario."));
        navegador().post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", ""))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Escriba su contraseña."));
    }

    @Test
    @DisplayName("contrato: cuerpo ilegible responde 400 «La petición no tiene el formato esperado.»")
    void paso1ConCuerpoIlegibleResponde400() throws Exception {
        navegador().post("/api/auth/ingreso", "{no es json")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.mensaje").value("La petición no tiene el formato esperado."));
    }

    // ---------- Bloqueo ----------

    @Test
    @DisplayName("IngresoYSesionApiTest:59 bloqueo: 5 erradas bloquean 15 minutos aunque luego sea la correcta; al vencer vuelve a entrar")
    void cincoErradasBloqueanQuinceMinutos() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        Navegador n = navegador();
        for (int i = 0; i < 5; i++) {
            generico401(paso1(n, u.usuario(), "Errada.Clave." + i));
        }
        assertThat(jdbc.sql("SELECT bloqueado_hasta IS NOT NULL FROM usuarios WHERE id = :u").param("u", u.id())
                .query(Boolean.class).single()).isTrue();
        generico401(paso1(n, u.usuario(), DatosPrueba.CLAVE));
        assertThat(correos.a(u.correo())).as("bloqueado: no se envía código").isEmpty();
        reloj.avanzar(Duration.ofMinutes(14));
        generico401(paso1(n, u.usuario(), DatosPrueba.CLAVE));
        reloj.avanzar(Duration.ofMinutes(2));
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:293 bloqueo: un ingreso correcto reinicia el contador; bloqueado no suma intentos")
    void ingresoCorrectoReiniciaIntentosYBloqueadoNoSuma() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        Navegador n = navegador();
        for (int i = 0; i < 4; i++) {
            paso1(n, u.usuario(), "Errada.Clave." + i);
        }
        assertThat(intentos(u.id())).isEqualTo(4);
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        assertThat(intentos(u.id())).as("el ingreso correcto reinicia").isZero();
        for (int i = 0; i < 5; i++) {
            paso1(n, u.usuario(), "Errada.Clave." + i);
        }
        int alBloquear = intentos(u.id());
        paso1(n, u.usuario(), "Otra.Errada.2026");
        assertThat(intentos(u.id())).as("bloqueado: no suma").isEqualTo(alBloquear);
    }

    // ---------- Paso 2 ----------

    @Test
    @DisplayName("IngresoYSesionApiTest:30 código correcto: 200 {usuario, nombre, continuar}, fila abierta en sesiones y ultimo_ingreso")
    void codigoCorrectoAbreSesionYResponde() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        ingresar(navegador(), u)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario").value(u.usuario()))
                .andExpect(jsonPath("$.nombre").value(u.nombre()))
                .andExpect(jsonPath("$.continuar").isNotEmpty());
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT ultimo_ingreso IS NOT NULL FROM usuarios WHERE id = :u").param("u", u.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("ADR-014: continuar es la autorización OIDC pendiente; seguirla emite el código y la sesión del emisor deja de servir")
    void continuarEsLaAutorizacionPendienteYSeConsumeAlEmitirElCodigo() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        var guardada = n.navegar(AUTORIZAR).andExpect(status().isFound()).andReturn();
        assertThat(ubicacion(guardada)).contains("/ingreso");
        var r = ingresar(n, u).andExpect(status().isOk());
        String continuar = Navegador.campo(r, "continuar");
        assertThat(continuar).startsWith("/oauth2/authorize?").contains("client_id=gateway");

        var emitido = n.navegar(continuar).andExpect(status().isFound()).andReturn();
        assertThat(ubicacion(emitido)).startsWith("http://localhost:9000/bff/login/oauth2/code/lume").contains("code=");
        var otra = n.navegar(AUTORIZAR).andExpect(status().isFound()).andReturn();
        assertThat(ubicacion(otra)).as("la sesión del emisor se invalidó al emitir el código").contains("/ingreso");
    }

    @Test
    @DisplayName("ADR-014: sin autorización pendiente, continuar es /bff/oauth2/authorization/lume")
    void sinAutorizacionPendienteContinuarEsElInicioDelBff() throws Exception {
        var u = datos.usuario();
        ingresar(navegador(), u).andExpect(status().isOk())
                .andExpect(jsonPath("$.continuar").value("/bff/oauth2/authorization/lume"));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:73 código incorrecto 422; tras 5 intentos se agota aunque luego llegue el correcto")
    void codigoIncorrectoYAgotamientoPorIntentos() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        String bueno = correos.ultimoCodigo(u.correo());
        String malo = bueno.equals("000000") ? "111111" : "000000";
        codigo(n, malo).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("El código no es correcto. Revise el último correo que le enviamos."));
        for (int i = 0; i < 4; i++) {
            codigo(n, malo);
        }
        codigo(n, bueno).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("El código venció o superó los intentos. Pida un código nuevo."));
        assertThat(sesionesAbiertas(u.id())).isZero();
    }

    @Test
    @DisplayName("contrato: código vacío 422 «Escriba el código.»")
    void codigoVacioEs422() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        codigo(n, "").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Escriba el código."));
    }

    @Test
    @DisplayName("contrato: código sin paso 1 previo responde 401 mfa_vencido")
    void codigoSinPaso1Responde401MfaVencido() throws Exception {
        codigo(navegador(), "123456").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("mfa_vencido"))
                .andExpect(jsonPath("$.mensaje").value("La verificación venció. Ingrese de nuevo con su usuario y contraseña."));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:259 el código vence a los 15 minutos del paso 1: 401 mfa_vencido")
    void codigoVenceALosQuinceMinutos() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        reloj.avanzar(Duration.ofMinutes(15).plusSeconds(1));
        codigo(n, correos.ultimoCodigo(u.correo())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.motivo").value("mfa_vencido"))
                .andExpect(jsonPath("$.mensaje").value("La verificación venció. Ingrese de nuevo con su usuario y contraseña."));
        assertThat(sesionesAbiertas(u.id())).isZero();
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:246 reenviar invalida el código anterior y deja vigente solo el último")
    void reenviarDejaVigenteSoloElUltimo() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        String primero = correos.ultimoCodigo(u.correo());
        n.post("/api/auth/codigo/reenviar").andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"))
                .andExpect(jsonPath("$.mensaje").value("Le enviamos un código nuevo."));
        String segundo = correos.ultimoCodigo(u.correo());
        assertThat(correos.a(u.correo())).hasSize(2);
        if (!primero.equals(segundo)) {
            codigo(n, primero).andExpect(status().isUnprocessableEntity());
        }
        codigo(n, segundo).andExpect(status().isOk());
    }

    @Test
    @DisplayName("contrato: reenviar sin paso 1 responde 401 mfa_vencido")
    void reenviarSinPaso1Responde401() throws Exception {
        navegador().post("/api/auth/codigo/reenviar").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.motivo").value("mfa_vencido"));
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:267 tope de códigos: el sexto en una hora da 429 con el texto del contrato")
    void sextoCodigoEnUnaHoraEs429() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        for (int i = 0; i < 5; i++) {
            paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        }
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.mensaje").value("Ya se enviaron 5 códigos en la última hora. Espere un momento antes de pedir otro."));
    }

    @Test
    @DisplayName("contrato: si el correo del código no sale, el paso 1 responde 502 con el texto del contrato")
    void correoDelCodigoQueFallaResponde502() throws Exception {
        var u = datos.usuario();
        correos.fallarSiguiente();
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.mensaje").value("No se pudo enviar el código a su correo. Intente de nuevo o avise al administrador."));
    }

    // ---------- Sesión única ----------

    @Test
    @DisplayName("IngresoYSesionApiTest:90 sesión única: con una sesión abierta el segundo ingreso responde 409 sesion_abierta y la primera sigue")
    void segundoIngresoConSesionAbiertaEs409() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        ingresar(navegador(), u).andExpect(status().isOk());
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("sesion_abierta"))
                .andExpect(jsonPath("$.mensaje").value(SESION_ABIERTA));
        assertThat(sesionesAbiertas(u.id())).as("la sesión abierta sigue").isEqualTo(1);
        assertThat(correos.a(u.correo())).as("el segundo ingreso no recibió otro código").hasSize(1);
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:111 sesión única: el código pedido antes de que la otra sesión abriera también se rechaza (paso 2)")
    void codigoPedidoAntesDeQueOtraSesionAbraEs409() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        Navegador equipoA = navegador();
        Navegador equipoB = navegador();
        paso1(equipoB, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        String codigoB = correos.ultimoCodigo(u.correo());
        ingresar(equipoA, u).andExpect(status().isOk());
        codigo(equipoB, codigoB).andExpect(status().isConflict()).andExpect(jsonPath("$.motivo").value("sesion_abierta"));
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("IngresoYSesionApiTest:124 sesión única: una sesión abandonada deja de bloquear al pasar los minutos de inactividad y queda cerrada por inactividad")
    void sesionAbandonadaSeLiberaPorInactividad() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        ingresar(navegador(), u).andExpect(status().isOk());
        reloj.avanzar(Duration.ofMinutes(9));
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isConflict());
        reloj.avanzar(Duration.ofMinutes(2));
        ingresar(navegador(), u).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT motivo FROM sesiones WHERE usuario_id = :u AND cerrada IS NOT NULL").param("u", u.id())
                .query(String.class).list()).containsExactly("inactividad");
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("contrato: los minutos de inactividad salen de seg_parametros.sesion_minutos (el texto del 409 dice N)")
    void minutosDeInactividadSalenDelParametro() throws Exception {
        var u = datos.usuario();
        jdbc.sql("INSERT INTO seg_parametros (clave, valor) VALUES ('sesion_minutos', '3') ON CONFLICT (clave) DO UPDATE SET valor = '3'")
                .update();
        parametros.olvidar(); // como lo hace la edición del administrador (S2): la caché de 30 s no retiene el valor viejo
        try {
            ingresar(navegador(), u).andExpect(status().isOk());
            paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.mensaje").value(SESION_ABIERTA.replace("10 minutos", "3 minutos")));
            reloj.avanzar(Duration.ofMinutes(4));
            paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk());
        } finally {
            // El rol de la aplicación no tiene DELETE sobre seg_parametros: se restaura el valor por defecto (10).
            jdbc.sql("UPDATE seg_parametros SET valor = '10' WHERE clave = 'sesion_minutos'").update();
            parametros.olvidar();
        }
    }

    // ---------- Estados de la cuenta en el paso 1 ----------

    @Test
    @DisplayName("IngresoYSesionApiTest:179 primer ingreso sin contraseña definida: no hay código, se envía el enlace para definirla")
    void primerIngresoEnviaEnlaceParaDefinirClave() throws Exception {
        var u = datos.nuevo().sinClaveDefinida().crear();
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("definir_clave"))
                .andExpect(jsonPath("$.mensaje").value("Es su primer ingreso: debe definir su contraseña. Le enviamos un enlace a su correo."));
        assertThat(correos.ultimoEnlace(u.correo())).matches("[a-f0-9]{64}");
        assertThat(sesionesAbiertas(u.id())).isZero();
    }

    @Test
    @DisplayName("contrato: cambio exigido por el administrador responde definir_clave «Debe actualizar su contraseña.» con enlace")
    void cambioExigidoPorElAdministradorEnviaEnlace() throws Exception {
        var u = datos.nuevo().debeCambiarClave().crear();
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("definir_clave"))
                .andExpect(jsonPath("$.mensaje").value("Debe actualizar su contraseña. Le enviamos un enlace a su correo."));
        assertThat(correos.ultimoEnlace(u.correo())).matches("[a-f0-9]{64}");
    }

    @Test
    @DisplayName("contrato: si el correo del enlace no sale, el mensaje dice «No se pudo enviar el correo: avise al administrador.»")
    void enlaceQueNoSaleAvisaAlAdministrador() throws Exception {
        var u = datos.nuevo().sinClaveDefinida().crear();
        correos.fallarSiguiente();
        paso1(navegador(), u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("definir_clave"))
                .andExpect(jsonPath("$.mensaje")
                        .value("Es su primer ingreso: debe definir su contraseña. No se pudo enviar el correo: avise al administrador."));
    }

    // ---------- Cambio de clave inicial / vencida dentro del ingreso ----------

    @Test
    @DisplayName("CuentaYSeguridadApiTest:412 clave vencida: el ingreso pide cambiarla ahí mismo, sin enlace al correo, y sigue el código")
    void claveVencidaSeCambiaEnElIngresoSinCorreoYSigueElCodigo() throws Exception {
        var u = datos.nuevo().claveVencida().crear();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("cambiar_clave"))
                .andExpect(jsonPath("$.mensaje").value(startsWith("Su contraseña venció (vigencia de ")))
                .andExpect(jsonPath("$.mensaje").value(endsWith("): defina una contraseña nueva.")));
        assertThat(correos.a(u.correo())).as("sin enlace al correo").isEmpty();

        n.post("/api/auth/cambiar-clave", Map.of("clave", DatosPrueba.CLAVE, "confirmacion", DatosPrueba.CLAVE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La nueva contraseña debe ser diferente a la actual."));
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"))
                .andExpect(jsonPath("$.mensaje").value("Contraseña actualizada. Le enviamos un código de 6 dígitos a su correo."));
        codigo(n, correos.ultimoCodigo(u.correo())).andExpect(status().isOk());
        assertThat(sesionesAbiertas(u.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT clave_vence > :ahora FROM usuarios WHERE id = :u")
                .param("ahora", reloj.instant().atOffset(ZoneOffset.UTC)).param("u", u.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:92 clave inicial: se cambia al ingresar, sin sesión hasta el código; la inicial deja de servir")
    void claveInicialSeCambiaAlIngresarYSigueConElCodigo() throws Exception {
        var u = datos.nuevo().claveInicial().crear();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.paso").value("cambiar_clave"))
                .andExpect(jsonPath("$.mensaje")
                        .value("Es su primer ingreso con la contraseña que le asignó el administrador: defina una contraseña nueva."));
        assertThat(sesionesAbiertas(u.id())).isZero();
        n.post("/api/auth/cambiar-clave", Map.of("clave", DatosPrueba.CLAVE, "confirmacion", DatosPrueba.CLAVE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La nueva contraseña debe ser diferente a la que le asignó el administrador."));
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", "Otra.Cosa.2026"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Las contraseñas no coinciden."));
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"));
        assertThat(jdbc.sql("SELECT clave_inicial OR debe_cambiar_clave FROM usuarios WHERE id = :u").param("u", u.id())
                .query(Boolean.class).single()).isFalse();

        generico401(paso1(navegador(), u.usuario(), DatosPrueba.CLAVE));
        paso1(navegador(), u.usuario(), NUEVA).andExpect(status().isOk()).andExpect(jsonPath("$.paso").value("codigo"));
        codigo(n, correos.ultimoCodigo(u.correo())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CuentaYSeguridadApiTest:428 un segundo envío de cambiar-clave ya no tiene el paso pendiente y responde 401")
    void segundoCambiarClaveSinPasoPendienteEs401() throws Exception {
        var u = datos.nuevo().claveVencida().crear();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA)).andExpect(status().isOk());
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA + "x", "confirmacion", NUEVA + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:129 cambiar-clave sin paso 1 previo responde 401 mfa_vencido")
    void cambiarClaveSinPaso1Responde401MfaVencido() throws Exception {
        navegador().post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("mfa_vencido"));
    }

    @Test
    @DisplayName("SesionesYClaveInicialApiTest:129 cambiar-clave aplica la política (mínimo 12) y exige la contraseña")
    void cambiarClaveValidaPoliticaYObligatoriedad() throws Exception {
        var u = datos.nuevo().claveInicial().crear();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        n.post("/api/auth/cambiar-clave", Map.of("clave", "", "confirmacion", ""))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.mensaje").value("Escriba la contraseña."));
        n.post("/api/auth/cambiar-clave", Map.of("clave", "Corta.1", "confirmacion", "Corta.1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("La contraseña debe tener mínimo 12 caracteres."));
    }

    @Test
    @DisplayName("contrato: la ventana de cambiar-clave es de 15 minutos desde el paso 1 (después, 401 mfa_vencido)")
    void cambiarClaveFueraDeLaVentanaDeQuinceMinutos() throws Exception {
        var u = datos.nuevo().claveInicial().crear();
        Navegador n = navegador();
        paso1(n, u.usuario(), DatosPrueba.CLAVE);
        reloj.avanzar(Duration.ofMinutes(15).plusSeconds(1));
        n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.motivo").value("mfa_vencido"));
    }
}
