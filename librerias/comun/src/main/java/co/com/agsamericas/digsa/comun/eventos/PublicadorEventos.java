package co.com.agsamericas.digsa.comun.eventos;

import java.util.UUID;

/**
 * Publica un evento de dominio por el outbox del servicio (ADR-006): lo inserta en la tabla {@code outbox} dentro de la transacción en
 * curso, que es obligatoria. Si la transacción hace rollback, el evento no existió. El {@link DespachadorOutbox} lo lleva después a
 * RabbitMQ, al exchange {@code <origen>.eventos}, con routing key = {@code tipo}.
 */
public interface PublicadorEventos {

    /**
     * @param tipo     {@code <agregado>.<hecho>} en minúsculas (p. ej. {@code usuario.creado})
     * @param agregado identificador del agregado (p. ej. {@code usuario:42}), hasta 100 caracteres
     * @param version  versión del agregado tras el cambio
     * @param datos    contenido; se serializa a JSON. Nunca se registra en los logs
     * @return el id del evento
     * @throws IllegalStateException    si no hay una transacción activa
     * @throws IllegalArgumentException si el tipo o el agregado no son válidos
     */
    UUID publicar(String tipo, String agregado, long version, Object datos);
}
