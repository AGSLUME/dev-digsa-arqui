package co.com.agsamericas.digsa.visitas.compartido;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoria;
import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.reglas.Bitacora;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Eventos de negocio de la bitácora (Anexo 5, prueba 15) con los tipos de {@link Bitacora#TIPOS} y los textos de {@code RegistroBitacora}
 * del monolito. Se publican por {@link PublicadorAuditoria} de lume-comun (hoy el log; con S3, el outbox hacia {@code seguridad}). La fila por
 * petición (el {@code @Accion} del monolito) la pone el filtro de comun en S3, no esta clase (integradora, 2026-11-06).
 *
 * <p>Como en el monolito, nunca lanza: una bitácora caída no deshace el cambio. El detalle va sin correos ({@link Bitacora#sinCorreos})
 * y quien llama nunca pone documentos ni datos de contacto (Ley 1581).
 */
@Component
public class BitacoraVisitas {

    private static final Logger LOG = LoggerFactory.getLogger(BitacoraVisitas.class);
    private static final String SERVICIO = "visitas";

    private final PublicadorAuditoria publicador;
    private final Clock reloj;

    public BitacoraVisitas(PublicadorAuditoria publicador, Clock reloj) {
        this.publicador = publicador;
        this.reloj = reloj;
    }

    /** Registra un hecho del usuario de la petición en curso. */
    public void registrar(String tipo, String detalle) {
        UsuarioActual u = UsuarioActual.actual().orElse(null);
        registrar(tipo, detalle, u == null ? null : u.uid(), u == null ? null : u.usuario());
    }

    public void registrar(String tipo, String detalle, Long uid, String usuario) {
        try {
            Bitacora.Operacion operacion = Bitacora.operacionDeTipo(tipo);
            publicador.publicar(new EventoAuditoria(reloj.instant(), SERVICIO, uid, usuario, operacion, tipo,
                    detalle == null ? null : Bitacora.sinCorreos(Bitacora.cortar(detalle, Bitacora.MAX_DETALLE)), null,
                    MDC.get("traceId")));
        } catch (RuntimeException e) {
            LOG.error("[bitacora] No se pudo publicar {}", tipo, e);
        }
    }
}
