package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Mfa;
import co.com.agsamericas.digsa.seguridad.correo.CorreoSeguridad;
import co.com.agsamericas.digsa.seguridad.correo.PlantillasCorreo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingreso en dos pasos (Anexo 5, 3.1 y 3.2), portado de {@code identidad.ServicioIngreso} del monolito: usuario y contraseña con captcha y
 * bloqueo tras 5 fallos; luego el código de 6 dígitos que llega al correo. Solo al superar el código se abre la sesión de LUME (fila en
 * {@code sesiones}, su id es el {@code sid}) y la sesión del emisor queda autenticada, con el {@code sid} como principal, para terminar la
 * autorización OIDC (ADR-014): la respuesta lleva {@code continuar}. Si el usuario ya tiene una sesión abierta y activa, el ingreso nuevo
 * se rechaza (decisión 7.1).
 *
 * <p>El estado del ingreso en curso (quién pasó el paso 1 y desde cuándo) vive en la sesión del emisor ({@code LUME_IDP}), como en el
 * monolito vivía en su sesión HTTP.
 */
@Service
public class ServicioIngreso {

    public static final String MENSAJE_GENERICO = "Usuario o contraseña incorrectos. Después de 5 intentos fallidos el usuario se bloquea 15 minutos.";
    public static final int INTENTOS_MAXIMOS = 5;
    public static final int MINUTOS_BLOQUEO = 15;
    /** Adonde va la SPA si el ingreso no empezó en una autorización del gateway: el gateway inicia una nueva (ADR-014). */
    public static final String CONTINUAR_POR_DEFECTO = "/bff/oauth2/authorization/lume";

    static final String MFA_USUARIO = "lume.ingreso.mfa.usuario";
    static final String MFA_DESDE = "lume.ingreso.mfa.desde";
    static final String CAMBIO_USUARIO = "lume.ingreso.cambio.usuario";
    static final String CAMBIO_DESDE = "lume.ingreso.cambio.desde";

    private static final String VERIFICACION_VENCIDA = "La verificación venció. Ingrese de nuevo con su usuario y contraseña.";
    private static final String RUTA_AUTORIZAR = "/oauth2/authorize";
    /** Clase del bloqueo consultivo por usuario al registrar un código (pg_advisory_xact_lock(clase, usuario)). */
    private static final int BLOQUEO_MFA = 0x4D4641;

    /** Respuesta del paso 1 (y del cambio de clave inicial y del reenvío). */
    public record Paso(String paso, String mensaje, String correo) {
    }

    /** Ingreso completo: quién entró y adónde sigue la SPA. */
    public record Completo(String usuario, String nombre, String continuar) {
    }

    private record CodigoMfa(long id, String huella, Instant vence, int intentos, boolean usado) {
    }

    private final RepositorioUsuarios usuarios;
    private final RepositorioSesiones sesiones;
    private final ServicioSesiones cierres;
    private final TransactionTemplate tx;
    private final ServicioClaves claves;
    private final Captcha captcha;
    private final CorreoSeguridad correo;
    private final PasswordEncoder codificador;
    private final BitacoraSeguridad bitacora;
    private final SecurityContextRepository contextos;
    private final RequestCache peticiones;
    private final PropiedadesSeguridad cfg;
    private final ParametrosSeguridad parametros;
    private final JdbcClient jdbc;
    private final Clock reloj;
    /** Hash de relleno: el tiempo de respuesta no revela si el usuario existe. */
    private final String hashRelleno;

    public ServicioIngreso(RepositorioUsuarios usuarios, RepositorioSesiones sesiones, ServicioSesiones cierres, TransactionTemplate tx,
                           ServicioClaves claves, Captcha captcha,
                           CorreoSeguridad correo, PasswordEncoder codificador, BitacoraSeguridad bitacora, SecurityContextRepository contextos,
                           RequestCache peticiones, PropiedadesSeguridad cfg, ParametrosSeguridad parametros, JdbcClient jdbc, Clock reloj) {
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.cierres = cierres;
        this.tx = tx;
        this.claves = claves;
        this.captcha = captcha;
        this.correo = correo;
        this.codificador = codificador;
        this.bitacora = bitacora;
        this.contextos = contextos;
        this.peticiones = peticiones;
        this.cfg = cfg;
        this.parametros = parametros;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.hashRelleno = codificador.encode(Huellas.secreto(16));
    }

    /**
     * Paso 1: usuario, contraseña y captcha. Si todo está bien, envía el código al correo. Sin transacción a propósito: cada sentencia
     * es atómica y así el captcha y el correo (llamadas externas) no retienen una conexión ni bloquean la fila del usuario.
     */
    public Paso ingresar(String usuarioEscrito, String clave, String respuestaCaptcha, HttpServletRequest req) {
        String escrito = usuarioEscrito == null ? "" : usuarioEscrito.strip().toLowerCase();
        String ip = req.getRemoteAddr();
        if (!captcha.superado(respuestaCaptcha, ip)) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "captcha_fallido", "Captcha no superado", null, Bitacora.enmascararUsuario(escrito), ip);
            throw ErrorNegocio.validacion("Por favor complete la verificación de seguridad (captcha).");
        }
        Instant ahora = reloj.instant();
        Optional<Usuario> encontrado = usuarios.porUsuario(escrito);
        String motivo = null;
        if (encontrado.isEmpty()) {
            coincide(clave, hashRelleno);
            motivo = "usuario inexistente";
        } else if (!encontrado.get().activo()) {
            coincide(clave, hashRelleno);
            motivo = "usuario inactivo";
        } else if (encontrado.get().bloqueado(ahora)) {
            coincide(clave, hashRelleno);
            motivo = "Intento con el usuario bloqueado";
        } else {
            // El intento se RESERVA antes de comparar (revisión de seguridad, M2): con la cuenta bloqueada o con 5 intentos ya en curso,
            // no se compara; así N peticiones en paralelo no hacen más de 5 comparaciones ni un acierto tardío borra el bloqueo.
            Optional<Integer> intento = usuarios.reservarIntento(encontrado.get().id(), ahora, INTENTOS_MAXIMOS);
            if (intento.isEmpty()) {
                coincide(clave, hashRelleno);
                motivo = "Intento con el usuario bloqueado";
            } else if (!coincide(clave, encontrado.get().claveHash())) {
                if (usuarios.bloquearSiAlcanzo(encontrado.get().id(), intento.get(), ahora, INTENTOS_MAXIMOS, MINUTOS_BLOQUEO)) {
                    bitacora.escribir(Bitacora.Operacion.INGRESO, "cuenta_bloqueada", "Usuario bloqueado " + MINUTOS_BLOQUEO
                            + " minutos por " + INTENTOS_MAXIMOS + " intentos fallidos", encontrado.get().id(), encontrado.get().usuario(), ip);
                }
                motivo = "contraseña incorrecta";
            }
        }
        if (motivo == null && encontrado.get().claveInicialVencida(ahora)) {
            motivo = "contraseña inicial vencida (" + RepositorioUsuarios.HORAS_CLAVE_INICIAL + " h): el administrador debe asignar otra";
        }
        if (motivo != null) {
            Long id = encontrado.map(Usuario::id).orElse(null);
            String quien = encontrado.map(Usuario::usuario).orElse(Bitacora.enmascararUsuario(escrito));
            bitacora.escribir(Bitacora.Operacion.INGRESO, "login_fallido", "Ingreso fallido: " + motivo, id, quien, ip);
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO);
        }
        Usuario u = encontrado.get();
        usuarios.reiniciarIntentos(u.id(), ahora);
        verificarSesionLibre(u, ahora, ip);
        HttpSession s = req.getSession(true);
        if (!s.isNew()) {
            req.changeSessionId(); // id nuevo tras la contraseña (conserva la autorización guardada): fijación de sesión
        }
        limpiarIngreso(s);
        if ((u.claveInicial() && u.debeCambiarClave()) || u.claveVencida(ahora)) {
            // Contraseña inicial del administrador o vencida: se cambia aquí mismo, sin ir al correo (prueba 2 y condición 12)
            s.setAttribute(CAMBIO_USUARIO, u.id());
            s.setAttribute(CAMBIO_DESDE, ahora.toEpochMilli());
            return new Paso("cambiar_clave", u.claveInicial()
                    ? "Es su primer ingreso con la contraseña que le asignó el administrador: defina una contraseña nueva."
                    : "Su contraseña venció (vigencia de " + cfg.claveVigenciaDias() + " días): defina una contraseña nueva.",
                    Correos.enmascarar(u.correo()));
        }
        if (u.cambioPendiente(ahora)) {
            String m = !u.claveDefinida() ? "Es su primer ingreso: debe definir su contraseña." : "Debe actualizar su contraseña.";
            CorreoSeguridad.Resultado e = claves.enviarEnlace(u, "Defina su contraseña", m, false);
            return new Paso("definir_clave", m + (e.ok() ? " Le enviamos un enlace a su correo." : " No se pudo enviar el correo: avise al administrador."),
                    Correos.enmascarar(u.correo()));
        }
        enviarCodigo(u, ip);
        s.setAttribute(MFA_USUARIO, u.id());
        s.setAttribute(MFA_DESDE, ahora.toEpochMilli());
        return new Paso("codigo", "Le enviamos un código de 6 dígitos a su correo.", Correos.enmascarar(u.correo()));
    }

    /** Reenvía el código al usuario que ya superó la contraseña (con el tope por hora). */
    public Paso reenviar(HttpServletRequest req) {
        Usuario u = usuarioPendiente(req);
        enviarCodigo(u, req.getRemoteAddr());
        return new Paso("codigo", "Le enviamos un código nuevo.", Correos.enmascarar(u.correo()));
    }

    /**
     * Paso 2: el código. Si es correcto, abre la sesión de LUME (si no hay otra abierta: decisión 7.1) y autentica la sesión del emisor.
     * El código se consume con un UPDATE condicionado (sin usar, con intentos y vigente): dos verificaciones a la vez no lo usan dos veces.
     */
    public Completo verificar(String codigo, HttpServletRequest req, HttpServletResponse res) {
        Usuario u = usuarioPendiente(req);
        Instant ahora = reloj.instant();
        String ip = req.getRemoteAddr();
        verificarSesionLibre(u, ahora, ip); // dos navegadores pudieron pasar el primer paso a la vez
        CodigoMfa c = jdbc.sql("""
                SELECT id, codigo_hash, vence, intentos, usado FROM mfa_codigos
                WHERE usuario_id = :u ORDER BY creado DESC, id DESC LIMIT 1""")
                .param("u", u.id())
                .query((rs, n) -> new CodigoMfa(rs.getLong("id"), rs.getString("codigo_hash"), rs.getTimestamp("vence").toInstant(),
                        rs.getInt("intentos"), rs.getTimestamp("usado") != null))
                .optional()
                .orElseThrow(() -> ErrorNegocio.validacion("No hay un código vigente. Pida un código nuevo."));
        // El intento se RESERVA antes de comparar, en una sola sentencia (revisión de seguridad, M1): N verificaciones en paralelo no
        // pueden hacer más de 5 comparaciones contra el mismo código. Sin fila = vencido, usado o agotado.
        Optional<Integer> reservado = jdbc.sql("""
                UPDATE mfa_codigos SET intentos = intentos + 1
                WHERE id = :id AND usado IS NULL AND intentos < :max AND vence >= :ahora RETURNING intentos""")
                .param("id", c.id()).param("max", Mfa.INTENTOS).param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .query(Integer.class).optional();
        if (reservado.isEmpty() || !Mfa.codigoValido(codigo, c.huella())) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_fallido", "Código de verificación incorrecto o vencido", u.id(), u.usuario(), ip);
            boolean agotado = reservado.map(i -> i >= Mfa.INTENTOS).orElse(true);
            throw ErrorNegocio.validacion(agotado ? "El código venció o superó los intentos. Pida un código nuevo."
                    : "El código no es correcto. Revise el último correo que le enviamos.");
        }
        int consumido = jdbc.sql("UPDATE mfa_codigos SET usado = :ahora WHERE id = :id AND usado IS NULL AND vence >= :ahora")
                .param("ahora", ahora.atOffset(ZoneOffset.UTC)).param("id", c.id()).update();
        if (consumido != 1) {
            throw ErrorNegocio.validacion("El código venció o ya se usó. Pida un código nuevo.");
        }

        int minutos = parametros.minutosSesion();
        UUID sid = cierres.abrir(u.id(), minutos, ip).orElseThrow(() -> {
            // Otra sesión del usuario se abrió mientras tanto
            bitacora.escribir(Bitacora.Operacion.INGRESO, "login_fallido", "Ingreso rechazado: el usuario ya tiene una sesión abierta", u.id(),
                    u.usuario(), ip);
            return sesionAbierta(minutos);
        });
        String continuar;
        try {
            usuarios.registrarIngreso(u.id(), ahora);
            continuar = continuar(req, res);
            // Sesión del emisor nueva (fijación de sesión): el id que circuló antes del ingreso deja de servir
            HttpSession anterior = req.getSession(false);
            if (anterior != null) {
                anterior.invalidate();
            }
            req.getSession(true);
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            // Dos factores con su hora (contraseña y código de un solo uso por correo): el emisor deriva de ellos el auth_time del id token
            // ArrayList (no List.of): la autorización guardada se lee con la lista de tipos permitidos de Jackson del emisor
            ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(sid.toString(), null, new java.util.ArrayList<>(List.of(
                    FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY).issuedAt(ahora).build(),
                    FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.OTT_AUTHORITY).issuedAt(ahora).build()))));
            SecurityContextHolder.setContext(ctx);
            contextos.saveContext(ctx, req, res);
        } catch (RuntimeException e) {
            // Sin esto la fila quedaría abierta sin dueño y el usuario recibiría 409 hasta que pase la inactividad (revisión Java, B10)
            cierres.cerrar(sid, RepositorioSesiones.Motivo.CERRADA);
            throw e;
        }
        bitacora.escribir(Bitacora.Operacion.INGRESO, "login", "Inicio de sesión", u.id(), u.usuario(), ip);
        return new Completo(u.usuario(), u.nombre(), continuar);
    }

    /**
     * Cambio de la contraseña dentro del ingreso, sin ir al correo: la inicial (asignada por el administrador) o la vencida. Después sigue
     * el código al correo.
     */
    public Paso cambiarClaveEnIngreso(String nueva, String confirmacion, HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Instant ahora = reloj.instant();
        Long uid = vigente(s, CAMBIO_USUARIO, CAMBIO_DESDE, ahora)
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, VERIFICACION_VENCIDA, Map.of("motivo", "mfa_vencido")));
        Optional<Usuario> enBase = usuarios.porId(uid).filter(Usuario::activo).filter(x -> !x.bloqueado(ahora));
        if (enBase.isPresent() && !enBase.get().claveInicial() && !enBase.get().claveVencida(ahora)) {
            throw ErrorNegocio.conflicto("La contraseña ya se cambió (¿envió el formulario dos veces?). Ingrese con la contraseña nueva.");
        }
        Usuario u = enBase.orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO));
        Claves.problema(nueva).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!nueva.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (coincide(nueva, u.claveHash())) {
            throw ErrorNegocio.validacion(u.claveInicial() ? "La nueva contraseña debe ser diferente a la que le asignó el administrador."
                    : "La nueva contraseña debe ser diferente a la actual.");
        }
        if (!usuarios.cambiarClave(u.id(), u.claveHash(), codificador.encode(nueva), ahora.plus(Duration.ofDays(cfg.claveVigenciaDias())), ahora)) {
            throw ErrorNegocio.conflicto("La contraseña ya se cambió (¿envió el formulario dos veces?). Ingrese con la contraseña nueva.");
        }
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_cambio", (u.claveInicial() ? "Contraseña inicial" : "Contraseña vencida")
                + " cambiada por el usuario " + u.usuario(), u.id(), u.usuario(), req.getRemoteAddr());
        s.removeAttribute(CAMBIO_USUARIO);
        s.removeAttribute(CAMBIO_DESDE);
        enviarCodigo(u, req.getRemoteAddr());
        s.setAttribute(MFA_USUARIO, u.id());
        s.setAttribute(MFA_DESDE, ahora.toEpochMilli());
        return new Paso("codigo", "Contraseña actualizada. Le enviamos un código de 6 dígitos a su correo.", Correos.enmascarar(u.correo()));
    }

    /** Decisión 7.1: con una sesión abierta y activa, el segundo ingreso se rechaza (no reemplaza a la anterior). */
    private void verificarSesionLibre(Usuario u, Instant ahora, String ip) {
        int minutos = parametros.minutosSesion();
        if (sesiones.bloqueaNuevoIngreso(u.id(), ahora, minutos)) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "login_fallido", "Ingreso rechazado: el usuario ya tiene una sesión abierta", u.id(),
                    u.usuario(), ip);
            throw sesionAbierta(minutos);
        }
    }

    /**
     * ¿La contraseña corresponde al hash? Nunca lanza: sin contraseña o sin hash verifica contra el hash de relleno (mismo tiempo) y
     * responde que no; más de 72 bytes (BCrypt los rechaza) cuenta como contraseña errada, no como error del servidor.
     */
    private boolean coincide(String clave, String hash) {
        try {
            if (clave == null || hash == null) {
                codificador.matches(clave == null ? "" : clave, hashRelleno);
                return false;
            }
            return codificador.matches(clave, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ErrorNegocio sesionAbierta(int minutos) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "Su usuario ya tiene una sesión abierta en otro equipo o navegador. Ciérrela con «Salir», "
                + "espere " + minutos + " minutos sin actividad o pida al administrador que la cierre. Solo se permite una sesión a la vez.",
                Map.of("motivo", "sesion_abierta"));
    }

    private Usuario usuarioPendiente(HttpServletRequest req) {
        Instant ahora = reloj.instant();
        Long uid = vigente(req.getSession(false), MFA_USUARIO, MFA_DESDE, ahora)
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, VERIFICACION_VENCIDA, Map.of("motivo", "mfa_vencido")));
        return usuarios.porId(uid).filter(Usuario::activo).filter(u -> !u.bloqueado(ahora))
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO));
    }

    /** El usuario guardado en la sesión del emisor, si su ventana de 15 minutos sigue abierta. */
    private static Optional<Long> vigente(HttpSession s, String atributoUsuario, String atributoDesde, Instant ahora) {
        Object id = s == null ? null : s.getAttribute(atributoUsuario);
        Object desde = s == null ? null : s.getAttribute(atributoDesde);
        if (!(id instanceof Long uid) || !(desde instanceof Long d)
                || Instant.ofEpochMilli(d).plusSeconds(Mfa.MINUTOS_VIGENCIA * 60L).isBefore(ahora)) {
            return Optional.empty();
        }
        return Optional.of(uid);
    }

    private static void limpiarIngreso(HttpSession s) {
        s.removeAttribute(MFA_USUARIO);
        s.removeAttribute(MFA_DESDE);
        s.removeAttribute(CAMBIO_USUARIO);
        s.removeAttribute(CAMBIO_DESDE);
    }

    /**
     * La autorización OIDC que inició el ingreso (la guardó el emisor al redirigir a {@code /ingreso}), como ruta relativa al origen.
     * Solo se acepta una petición a {@code /oauth2/authorize}: {@code continuar} nunca lleva a otro sitio (redirección abierta).
     */
    private String continuar(HttpServletRequest req, HttpServletResponse res) {
        SavedRequest guardada = peticiones.getRequest(req, res);
        if (guardada == null) {
            return CONTINUAR_POR_DEFECTO;
        }
        peticiones.removeRequest(req, res);
        try {
            URI u = URI.create(guardada.getRedirectUrl());
            if (!RUTA_AUTORIZAR.equals(u.getRawPath()) || u.getRawQuery() == null) {
                return CONTINUAR_POR_DEFECTO;
            }
            return RUTA_AUTORIZAR + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return CONTINUAR_POR_DEFECTO;
        }
    }

    private void enviarCodigo(Usuario u, String ip) {
        Instant ahora = reloj.instant();
        int tope = cfg.mfa().codigosPorHora();
        String codigo = Huellas.codigoSeisDigitos();
        // Primero se registra (cuenta para el tope por hora) y después sale el correo: nunca un código enviado sin registro. Conteo e
        // inserción con un bloqueo consultivo por usuario: varios «reenviar» a la vez no pasan el tope (revisión Java, M4).
        Long id = tx.execute(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:clase, :u)").param("clase", BLOQUEO_MFA).param("u", (int) u.id())
                    .query(Object.class).optional();
            long ultimaHora = jdbc.sql("SELECT count(*) FROM mfa_codigos WHERE usuario_id = :u AND creado > :desde")
                    .param("u", u.id()).param("desde", ahora.minusSeconds(3600).atOffset(ZoneOffset.UTC)).query(Long.class).single();
            if (!Mfa.puedeEnviarOtro((int) ultimaHora, tope)) {
                return null;
            }
            return jdbc.sql("""
                    INSERT INTO mfa_codigos (usuario_id, codigo_hash, creado, vence, ip) VALUES (:u, :h, :creado, :vence, :ip) RETURNING id""")
                    .param("u", u.id()).param("h", Huellas.sha256(codigo)).param("creado", ahora.atOffset(ZoneOffset.UTC))
                    .param("vence", Mfa.vence(ahora).atOffset(ZoneOffset.UTC)).param("ip", ip).query(Long.class).single();
        });
        if (id == null) {
            throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Ya se enviaron " + tope
                    + " códigos en la última hora. Espere un momento antes de pedir otro.");
        }
        CorreoSeguridad.Resultado r = correo.enviar(PlantillasCorreo.codigoMfa(u.id(), u.correo(), u.nombre(), codigo));
        if (!r.ok()) {
            jdbc.sql("UPDATE mfa_codigos SET usado = :ahora WHERE id = :id").param("ahora", ahora.atOffset(ZoneOffset.UTC)).param("id", id).update();
            bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_enviado", "El código de verificación NO salió: " + r.error(), u.id(), u.usuario(), ip);
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el código a su correo. Intente de nuevo o avise al administrador.");
        }
        bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_enviado", "Código de verificación enviado", u.id(), u.usuario(), ip);
    }
}
