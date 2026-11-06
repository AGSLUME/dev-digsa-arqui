package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoria;
import co.com.agsamericas.digsa.reglas.Bitacora;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Eventos de la bitácora de la identidad ({@code captcha_fallido}, {@code login_fallido}, {@code mfa_enviado}, {@code login}, ...), con los
 * mismos tipos y textos que {@code RegistroBitacora} del monolito. Los publica por {@link PublicadorAuditoria} de lume-comun (hoy el log;
 * en S3, el outbox). Como en el monolito, nunca lanza: una bitácora caída no impide ingresar.
 */
@Component
public class BitacoraSeguridad {

    private static final Logger LOG = LoggerFactory.getLogger(BitacoraSeguridad.class);
    private static final String SERVICIO = "seguridad";

    private final PublicadorAuditoria publicador;
    private final Clock reloj;

    public BitacoraSeguridad(PublicadorAuditoria publicador, Clock reloj) {
        this.publicador = publicador;
        this.reloj = reloj;
    }

    /**
     * @param uid       usuario al que se refiere, o null (p. ej. un usuario inexistente)
     * @param usuario   nombre de usuario (enmascarado si lo escribió alguien sin identificar)
     * @param detalle   texto sin secretos ni datos personales (Ley 1581): se le quitan los correos
     */
    public void escribir(Bitacora.Operacion operacion, String tipo, String detalle, Long uid, String usuario, String ip) {
        try {
            publicador.publicar(new EventoAuditoria(reloj.instant(), SERVICIO, uid, usuario, operacion, tipo,
                    detalle == null ? null : Bitacora.sinCorreos(detalle), ip, MDC.get("traceId")));
        } catch (RuntimeException e) {
            LOG.error("[bitacora] No se pudo publicar {}", tipo, e);
        }
    }
}
