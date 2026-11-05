package co.com.agsamericas.digsa.comun.auditoria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publicador provisional: deja el evento en el log estructurado (logger {@code lume.auditoria}). Lo reemplaza el outbox en S3. */
public class PublicadorAuditoriaLog implements PublicadorAuditoria {

    private static final Logger LOG = LoggerFactory.getLogger("lume.auditoria");

    @Override
    public void publicar(EventoAuditoria e) {
        LOG.atInfo()
                .addKeyValue("servicio", e.servicio())
                .addKeyValue("uid", e.uid())
                .addKeyValue("usuario", e.usuario())
                .addKeyValue("operacion", e.operacion().valor())
                .addKeyValue("tipo", e.tipo())
                .addKeyValue("detalle", e.detalle())
                .addKeyValue("ip", e.ip())
                .addKeyValue("traza", e.traza())
                .log("auditoria {}", e.tipo());
    }
}
