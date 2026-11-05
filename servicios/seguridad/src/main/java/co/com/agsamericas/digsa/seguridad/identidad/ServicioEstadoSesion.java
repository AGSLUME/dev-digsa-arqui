package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.seguridad.correo.CorreoSeguridad;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Fila;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * El estado de una sesión de LUME, con las reglas de {@code identidad.FiltroSesion} del monolito (que revalidaba en cada petición). Lo
 * consulta el gateway en cada petición (caché ≤30 s) y también {@code /api/sesion*} del propio servicio. Orden del monolito: usuario
 * inactivo → contraseña cambiada después del inicio → sesión cerrada (salida, administrador, otro ingreso, bloqueo) → contraseña
 * vencida → cambio exigido (envía el enlace). La inactividad la mide el gateway (tiene la hora exacta de cada petición) con los minutos
 * que se le devuelven, y cierra con {@link Motivo#INACTIVIDAD}; aquí solo se usa la actividad informada para la decisión 7.1.
 */
@Service
public class ServicioEstadoSesion {

    /** Textos del 401 por motivo (los del monolito; {@code sin_sesion} es el del punto de entrada de Spring Security). */
    public static final Map<String, String> MENSAJES = Map.of(
            "sin_sesion", "La sesión expiró. Vuelva a iniciar sesión.",
            "inactividad", "La sesión se cerró por inactividad. Vuelva a iniciar sesión.",
            "inactivo", "Su usuario está inactivo.",
            "clave", "La contraseña cambió. Inicie sesión de nuevo.",
            "cerrada", "Su sesión fue cerrada. Vuelva a iniciar sesión.",
            "reemplazada", "Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.",
            "clave_vencida", "Su contraseña venció. Ingrese de nuevo para cambiarla.",
            "cambio_clave", "Debe actualizar su contraseña.",
            "bloqueado", "Su usuario está bloqueado " + ServicioIngreso.MINUTOS_BLOQUEO
                    + " minutos por intentos fallidos. Vuelva a ingresar más tarde.");

    /**
     * @param vigente  si la sesión sigue abierta y válida
     * @param minutos  minutos de inactividad vigentes
     * @param uid      usuario de la sesión (si se conoce)
     * @param ultimo   última actividad informada
     * @param motivo   si no es vigente, el {@code motivo} del 401
     * @param mensaje  si no es vigente, el texto del 401
     */
    public record Estado(boolean vigente, int minutos, Long uid, Instant ultimo, String motivo, String mensaje) {

        static Estado vigente(int minutos, long uid, Instant ultimo) {
            return new Estado(true, minutos, uid, ultimo, null, null);
        }

        static Estado cerrada(int minutos, Long uid, String motivo, String mensaje) {
            return new Estado(false, minutos, uid, null, motivo, mensaje == null ? MENSAJES.get(motivo) : mensaje);
        }

        /** Segundos que le quedan antes de cerrarse por inactividad, según la última actividad informada. */
        public long restantes(Instant ahora) {
            if (!vigente || ultimo == null) {
                return 0;
            }
            return Math.max(0, minutos * 60L - Duration.between(ultimo, ahora).toSeconds());
        }
    }

    private final RepositorioSesiones sesiones;
    private final RepositorioUsuarios usuarios;
    private final ServicioSesiones cierres;
    private final ServicioClaves claves;
    private final ParametrosSeguridad parametros;
    private final BitacoraSeguridad bitacora;
    private final Clock reloj;

    public ServicioEstadoSesion(RepositorioSesiones sesiones, RepositorioUsuarios usuarios, ServicioSesiones cierres, ServicioClaves claves,
                                ParametrosSeguridad parametros, BitacoraSeguridad bitacora, Clock reloj) {
        this.sesiones = sesiones;
        this.usuarios = usuarios;
        this.cierres = cierres;
        this.claves = claves;
        this.parametros = parametros;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    /** Evalúa la sesión y, si una regla la cierra, la cierra (con revocación) y deja la salida en la bitácora. */
    public Estado evaluar(UUID sid) {
        int minutos = parametros.minutosSesion();
        Optional<Fila> encontrada = sid == null ? Optional.empty() : sesiones.porId(sid);
        if (encontrada.isEmpty()) {
            return Estado.cerrada(minutos, null, "sin_sesion", null);
        }
        Fila s = encontrada.get();
        Optional<Usuario> u = usuarios.porId(s.usuarioId());
        if (u.isEmpty() || !u.get().activo()) {
            // Monolito, caso 2: la salida ya quedó en la bitácora al inactivar (decisión 7.3)
            cerrarSiAbierta(s, Motivo.INACTIVO);
            return Estado.cerrada(minutos, s.usuarioId(), "inactivo", null);
        }
        Usuario usuario = u.get();
        if (!s.abierta()) {
            return Estado.cerrada(minutos, s.usuarioId(), motivoDeCierre(s.motivo()), null);
        }
        Instant ahora = reloj.instant();
        if (usuario.claveActualizada() != null && usuario.claveActualizada().isAfter(s.inicio())) {
            cerrarSiAbierta(s, Motivo.CLAVE);
            return Estado.cerrada(minutos, s.usuarioId(), "clave", null);
        }
        if (usuario.cambioPendiente(ahora)) {
            if (usuario.claveVencida(ahora)) {
                cerrar(s, usuario, Motivo.CLAVE_VENCIDA, "Sesión cerrada: la contraseña venció");
                return Estado.cerrada(minutos, s.usuarioId(), "clave_vencida", null);
            }
            if (cerrar(s, usuario, Motivo.CAMBIO_CLAVE, "Sesión cerrada: debe actualizar su contraseña")) {
                CorreoSeguridad.Resultado e = claves.enviarEnlace(usuario, "Defina su contraseña", "Debe actualizar su contraseña.", false);
                return Estado.cerrada(minutos, s.usuarioId(), "cambio_clave",
                        MENSAJES.get("cambio_clave") + (e.ok() ? " Le enviamos un enlace a su correo." : ""));
            }
            return Estado.cerrada(minutos, s.usuarioId(), "cambio_clave", null);
        }
        return Estado.vigente(minutos, s.usuarioId(), s.ultimo());
    }

    /** «Seguir conectado» o actividad informada por el gateway. @return false si la sesión ya no está abierta */
    public boolean tocar(UUID sid) {
        return sesiones.tocar(sid, reloj.instant());
    }

    /** Cierre pedido por el gateway: salida o inactividad (con su fila en la bitácora). */
    public void cerrarPorGateway(UUID sid, Motivo motivo) {
        sesiones.porId(sid).ifPresent(s -> {
            if (cierres.cerrar(sid, motivo)) {
                String usuario = usuarios.porId(s.usuarioId()).map(Usuario::usuario).orElse(null);
                if (motivo == Motivo.SALIDA) {
                    bitacora.escribir(Bitacora.Operacion.SALIDA, "logout", "Cierre de sesión", s.usuarioId(), usuario, null);
                } else {
                    bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_expirada", "Sesión cerrada por inactividad", s.usuarioId(), usuario, null);
                }
            }
        });
    }

    /** El motivo del 401 para una sesión ya cerrada: la salida propia es un «sin sesión» común. */
    static String motivoDeCierre(Motivo m) {
        return m == null || m == Motivo.SALIDA ? "sin_sesion" : m.valor();
    }

    private void cerrarSiAbierta(Fila s, Motivo m) {
        if (s.abierta()) {
            cierres.cerrar(s.id(), m);
        }
    }

    private boolean cerrar(Fila s, Usuario u, Motivo m, String detalle) {
        boolean cerro = cierres.cerrar(s.id(), m);
        if (cerro) {
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", detalle, u.id(), u.usuario(), null);
        }
        return cerro;
    }
}
