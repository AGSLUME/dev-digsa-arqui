package co.com.agsamericas.digsa.comun.auditoria;

/**
 * Publica un evento de auditoría. La implementación definitiva (fase S3) lo escribe en el outbox del servicio, en la misma transacción
 * que el cambio de negocio, y de ahí sale a RabbitMQ (ADR-006). Mientras tanto, {@link PublicadorAuditoriaLog} lo deja en el log.
 */
public interface PublicadorAuditoria {

    /** En la misma transacción del cambio (con el outbox, en S3): si falla, la operación falla. */
    void publicar(EventoAuditoria evento);

    /**
     * Para lo que fracasa o se deniega (ingreso fallido, captcha, MFA, 403, bloqueo), cuya transacción principal se revierte: con el
     * outbox (S3) irá en una transacción propia (REQUIRES_NEW) y también funciona fuera de una transacción. Hoy, igual que
     * {@link #publicar}.
     */
    default void publicarIndependiente(EventoAuditoria evento) {
        publicar(evento);
    }
}
