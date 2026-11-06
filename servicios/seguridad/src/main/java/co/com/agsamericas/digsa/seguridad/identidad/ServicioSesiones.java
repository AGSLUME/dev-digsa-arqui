package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de sesiones con revocación inmediata (ADR-004): al cerrar una sesión se borran sus autorizaciones OAuth2 (el refresh token deja
 * de servir) y la sesión del emisor ({@code LUME_IDP}) que quedara. Ambas tienen como principal el {@code sid} (ver
 * {@code emisor.ConfiguracionEmisor}), así que se encuentran por él. El gateway, además, deja de aceptar el {@code sid} en su siguiente
 * consulta de estado (caché de 30 s como máximo) y, en S2, al recibir {@code sesion.cerrada}.
 */
@Service
public class ServicioSesiones {

    private final RepositorioSesiones sesiones;
    private final JdbcClient jdbc;
    private final Clock reloj;

    public ServicioSesiones(RepositorioSesiones sesiones, JdbcClient jdbc, Clock reloj) {
        this.sesiones = sesiones;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    /**
     * Abre la sesión del usuario (decisión 7.1). Con la fila del usuario bloqueada: cierra por inactividad (y revoca) la abierta que ya
     * superó el plazo y abre la nueva solo si no queda otra abierta.
     *
     * @return el {@code sid} nuevo, o vacío si otra sesión sigue abierta y activa
     */
    @Transactional
    public Optional<UUID> abrir(long usuarioId, int minutos, String ip) {
        Instant ahora = reloj.instant();
        sesiones.bloquearUsuario(usuarioId);
        sesiones.cerrarInactivas(usuarioId, ahora, minutos).forEach(this::revocar);
        return sesiones.insertar(usuarioId, ahora, ip);
    }

    /** Cierra una sesión y revoca lo que dependa de ella. @return true si estaba abierta */
    @Transactional
    public boolean cerrar(UUID sid, Motivo motivo) {
        boolean cerrada = sesiones.cerrar(sid, motivo, reloj.instant());
        revocar(sid);
        return cerrada;
    }

    /** Cierra la sesión abierta del usuario, si la hay. @return true si había una */
    @Transactional
    public boolean cerrarDeUsuario(long usuarioId, Motivo motivo) {
        List<UUID> cerradas = sesiones.cerrarDeUsuario(usuarioId, motivo, reloj.instant());
        cerradas.forEach(this::revocar);
        return !cerradas.isEmpty();
    }

    private void revocar(UUID sid) {
        String principal = sid.toString();
        jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = :p").param("p", principal).update();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name = :p").param("p", principal).update();
    }
}
