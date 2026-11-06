package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.seguridad.correo.CorreoSeguridad;
import co.com.agsamericas.digsa.seguridad.correo.PlantillasCorreo;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contraseñas fuera del ingreso, portado de {@code identidad.ServicioClaves} del monolito: enlace de un solo uso al correo (60 minutos,
 * en la base solo su SHA-256), «olvidé mi contraseña» (siempre la misma respuesta, uno cada 5 minutos) y «cambiar mi contraseña» con la
 * sesión abierta. Política de claves de {@code reglas/Claves}.
 */
@Service
public class ServicioClaves {

    /** Enlaces de recuperación de un mismo usuario: como máximo uno cada 5 minutos (duda 17.16). */
    static final int MINUTOS_ENTRE_ENLACES = 5;

    private static final Logger LOG = LoggerFactory.getLogger(ServicioClaves.class);

    private final RepositorioUsuarios usuarios;
    private final RepositorioSesiones sesiones;
    private final ServicioSesiones cierres;
    private final CorreoSeguridad correo;
    private final PasswordEncoder codificador;
    private final BitacoraSeguridad bitacora;
    private final PropiedadesSeguridad cfg;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private final EventosUsuario eventos;
    /** «Olvidé mi contraseña» fuera de la petición; cola acotada: una avalancha de solicitudes no agota la memoria ni el SMTP. */
    private final ThreadPoolExecutor hilosOlvido = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            Thread.ofPlatform().name("lume-olvido").daemon(true).factory());

    public ServicioClaves(RepositorioUsuarios usuarios, RepositorioSesiones sesiones, ServicioSesiones cierres, CorreoSeguridad correo,
                          PasswordEncoder codificador, BitacoraSeguridad bitacora, PropiedadesSeguridad cfg, TransactionTemplate tx,
                          Clock reloj, EventosUsuario eventos) {
        this.eventos = eventos;
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.cierres = cierres;
        this.correo = correo;
        this.codificador = codificador;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.tx = tx;
        this.reloj = reloj;
    }

    /**
     * Genera un enlace nuevo (reemplaza al anterior) y lo envía. {@code exigirCambio} marca el cambio obligatorio (restablecer del
     * administrador); «olvidé mi contraseña» no lo marca ni cierra sesiones. Sin transacción: el token queda guardado antes de que salga el
     * correo, y el envío no retiene una conexión.
     */
    public CorreoSeguridad.Resultado enviarEnlace(Usuario u, String asunto, String motivo, boolean exigirCambio) {
        Instant ahora = reloj.instant();
        String token = Huellas.secreto(32);
        usuarios.guardarToken(u.id(), Huellas.sha256(token), ahora.plus(Duration.ofMinutes(Claves.MINUTOS_ENLACE)), exigirCambio, ahora);
        String enlace = cfg.enlace("definir-clave?tkn=" + token);
        return correo.enviar(PlantillasCorreo.enlaceClave(u.id(), u.correo(), asunto, u.nombre(), motivo, enlace, Claves.MINUTOS_ENLACE));
    }

    /**
     * Como {@link #enviarEnlace}, pero sin reenviar si ya salió uno en los últimos {@link #MINUTOS_ENTRE_ENLACES} minutos (revisión B-f: el
     * paso 1 de un usuario que debe definir su contraseña no puede servir para inundar su correo). Responde «enviado» porque el enlace
     * vigente ya está en su correo.
     */
    public CorreoSeguridad.Resultado enviarEnlaceConTope(Usuario u, String asunto, String motivo) {
        if (enviadoHacePoco(u, reloj.instant())) {
            return CorreoSeguridad.Resultado.bien();
        }
        return enviarEnlace(u, asunto, motivo, false);
    }

    private static boolean enviadoHacePoco(Usuario u, Instant ahora) {
        Instant enviado = u.tokenVence() == null ? null : u.tokenVence().minus(Duration.ofMinutes(Claves.MINUTOS_ENLACE));
        return enviado != null && enviado.plus(Duration.ofMinutes(MINUTOS_ENTRE_ENLACES)).isAfter(ahora);
    }

    /** Usuario del enlace, si sigue vigente. */
    public Optional<Usuario> usuarioDelEnlace(String token) {
        if (token == null || !token.matches("^[a-f0-9]{64}$")) {
            return Optional.empty();
        }
        Instant ahora = reloj.instant();
        return usuarios.porTokenHash(Huellas.sha256(token))
                .filter(Usuario::activo)
                .filter(u -> Claves.enlaceVigente(u.tokenHash(), u.tokenVence(), token, ahora));
    }

    /**
     * Define la contraseña con el enlace. Consume el enlace y cierra la sesión que hubiera abierta (sesión única, decisión 7.3). La
     * validación y el BCrypt van fuera de la transacción: no retienen una conexión del pool (revisión Java, M6).
     */
    public void definir(String token, String clave, String confirmacion) {
        Usuario u = usuarioDelEnlace(token).orElseThrow(() -> ErrorNegocio.validacion(
                "El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?»."));
        Claves.problema(clave).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!clave.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (u.claveDefinida() && coincide(clave, u.claveHash())) {
            throw ErrorNegocio.validacion("La nueva contraseña debe ser diferente a la actual.");
        }
        Instant ahora = reloj.instant();
        String hash = codificador.encode(clave);
        Boolean cerroSesion = tx.execute(s -> {
            if (!usuarios.definirClave(u.id(), u.tokenHash(), hash, ahora.plus(Duration.ofDays(cfg.claveVigenciaDias())), ahora)) {
                throw ErrorNegocio.validacion("El enlace ya se usó. Pida uno nuevo con «¿Olvidó su contraseña?».");
            }
            return cierres.cerrarDeUsuario(u.id(), Motivo.CLAVE);
        });
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_cambio", "Contraseña definida por enlace del usuario " + u.usuario(), u.id(),
                u.usuario(), null);
        if (Boolean.TRUE.equals(cerroSesion)) {
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "Sesión cerrada por cambio de contraseña", u.id(), u.usuario(), null);
        }
    }

    /**
     * «Olvidé mi contraseña»: responde siempre lo mismo y en el mismo tiempo para no revelar quién está registrado (duda 17.16): la
     * búsqueda y el correo van en un hilo aparte. Nunca devuelve el enlace: solo llega al correo.
     */
    public void olvide(String valor) {
        String v = valor == null ? "" : valor.strip().toLowerCase();
        try {
            hilosOlvido.execute(() -> enviarRecuperacion(v));
        } catch (RejectedExecutionException e) {
            // Muchas solicitudes a la vez: se descarta (la respuesta es la misma) y queda en la bitácora, sin lo escrito (puede ser un correo)
            LOG.warn("[claves] Solicitud de recuperación descartada: cola llena ({} en espera)", hilosOlvido.getQueue().size());
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "Solicitud de recuperación descartada: demasiadas a la vez",
                    null, null, null);
        }
    }

    private void enviarRecuperacion(String valor) {
        try {
            Optional<Usuario> u = usuarios.porUsuarioOCorreo(valor).filter(Usuario::activo);
            if (u.isEmpty()) {
                return;
            }
            Instant ahora = reloj.instant();
            Instant enviado = u.get().tokenVence() == null ? null : u.get().tokenVence().minus(Duration.ofMinutes(Claves.MINUTOS_ENLACE));
            if (enviado != null && enviado.plus(Duration.ofMinutes(MINUTOS_ENTRE_ENLACES)).isAfter(ahora)) {
                bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "Recuperación pedida de nuevo para " + u.get().usuario()
                        + ": no se envía otro enlace antes de " + MINUTOS_ENTRE_ENLACES + " minutos", u.get().id(), u.get().usuario(), null);
                return;
            }
            CorreoSeguridad.Resultado e = enviarEnlace(u.get(), "Recuperación de contraseña",
                    "Recibimos una solicitud para definir una contraseña nueva en la Suite DIGSA.", false);
            if (!e.ok()) {
                usuarios.anularEnlace(u.get().id()); // un enlace que no salió no cuenta para el tope: el siguiente pedido lo intenta de nuevo
            }
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "Enlace de recuperación para " + u.get().usuario()
                    + (e.ok() ? "" : " (el correo NO salió)"), u.get().id(), u.get().usuario(), null);
        } catch (RuntimeException e) {
            // Solo la clase: el mensaje de la excepción puede llevar el correo (dato personal)
            LOG.error("[claves] No se pudo atender la recuperación de contraseña: {}", e.getClass().getName());
        }
    }

    /**
     * El usuario con sesión cambia su contraseña («Cambiar mi contraseña»), sin correo. Exige la actual: una actual errada cuenta como
     * intento fallido y, al quinto, el usuario se bloquea y la sesión se cierra. La sesión en curso sigue abierta: su inicio pasa a ser
     * posterior al cambio, así que la revisión de «la contraseña cambió» no la cierra (en el monolito: {@code INICIO = ahora + 1}).
     *
     * @param sid la sesión desde la que se hace el cambio (claim {@code sid} del token)
     */
    public void cambiarPropia(long usuarioId, UUID sid, String actual, String nueva, String confirmacion, String ip) {
        Instant ahora = reloj.instant();
        Usuario u = usuarios.porId(usuarioId).filter(Usuario::activo).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        if (u.bloqueado(ahora)) {
            // Bloqueado por intentos (también desde el ingreso): sin intentos ilimitados aquí ni desbloqueo por acertar
            cerrarPorBloqueo(u, "Sesión cerrada: el usuario está bloqueado por intentos fallidos", ip);
        }
        if (!coincide(actual, u.claveHash())) {
            // El bloqueo y su cuenta.bloqueada, en una sola transacción (outbox)
            if (Boolean.TRUE.equals(tx.execute(t -> {
                boolean bloqueo = usuarios.registrarFallo(u.id(), ahora, ServicioIngreso.INTENTOS_MAXIMOS, ServicioIngreso.MINUTOS_BLOQUEO);
                if (bloqueo) {
                    eventos.publicar("cuenta.bloqueada", u.id());
                }
                return bloqueo;
            }))) {
                bitacora.escribir(Bitacora.Operacion.INGRESO, "cuenta_bloqueada", "Usuario bloqueado " + ServicioIngreso.MINUTOS_BLOQUEO
                        + " minutos por " + ServicioIngreso.INTENTOS_MAXIMOS + " intentos fallidos", u.id(), u.usuario(), ip);
                cerrarPorBloqueo(u, "Sesión cerrada: " + ServicioIngreso.INTENTOS_MAXIMOS + " contraseñas actuales erradas al cambiarla", ip);
            }
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_cambio_fallido", "Contraseña actual errada al cambiarla", u.id(),
                    u.usuario(), ip);
            throw ErrorNegocio.validacion("La contraseña actual no es correcta.");
        }
        Claves.problema(nueva).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!nueva.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (coincide(nueva, u.claveHash())) {
            throw ErrorNegocio.validacion("La nueva contraseña debe ser diferente a la actual.");
        }
        if (!usuarios.cambiarClave(u.id(), u.claveHash(), codificador.encode(nueva), ahora.plus(Duration.ofDays(cfg.claveVigenciaDias())), ahora)) {
            throw ErrorNegocio.conflicto("La contraseña cambió mientras tanto. Recargue e intente de nuevo.");
        }
        if (sid != null) {
            sesiones.renovarInicio(sid, ahora.plusMillis(1));
        }
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_cambio", "Contraseña cambiada por el usuario " + u.usuario()
                + " desde su sesión", u.id(), u.usuario(), ip);
    }

    /** ¿La contraseña corresponde al hash? Nunca lanza: más de 72 bytes (BCrypt los rechaza) cuenta como no coincidente. */
    private boolean coincide(String clave, String hash) {
        try {
            return clave != null && hash != null && codificador.matches(clave, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void cerrarPorBloqueo(Usuario u, String motivo, String ip) {
        cierres.cerrarDeUsuario(u.id(), Motivo.BLOQUEADO);
        bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", motivo, u.id(), u.usuario(), ip);
        throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, "Su usuario está bloqueado " + ServicioIngreso.MINUTOS_BLOQUEO
                + " minutos por intentos fallidos. Vuelva a ingresar más tarde.", Map.of("motivo", "bloqueado"));
    }

    /** Al apagar, espera unos segundos a que salgan las recuperaciones en cola; las que queden se registran como perdidas. */
    @PreDestroy
    void detener() throws InterruptedException {
        hilosOlvido.shutdown();
        if (!hilosOlvido.awaitTermination(10, TimeUnit.SECONDS)) {
            LOG.error("[claves] Al apagar quedaron {} recuperaciones de contraseña sin atender", hilosOlvido.getQueue().size());
        }
    }
}
