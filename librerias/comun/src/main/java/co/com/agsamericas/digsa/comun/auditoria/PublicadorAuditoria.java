package co.com.agsamericas.digsa.comun.auditoria;

/**
 * Publica un evento de auditoría. La implementación definitiva (fase S3) lo escribe en el outbox del servicio, en la misma transacción
 * que el cambio de negocio, y de ahí sale a RabbitMQ (ADR-006). Mientras tanto, {@link PublicadorAuditoriaLog} lo deja en el log.
 */
public interface PublicadorAuditoria {

    void publicar(EventoAuditoria evento);
}
