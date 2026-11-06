package co.com.agsamericas.digsa.comun.eventos;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * El sobre común de todos los eventos de LUME (ADR-006). Es el cuerpo JSON del mensaje.
 *
 * @param id       id único del evento (también el {@code messageId}): la llave de idempotencia del consumidor
 * @param tipo     {@code <agregado>.<hecho>}, igual a la routing key
 * @param version  versión del agregado: el consumidor descarta las menores o iguales a la que ya aplicó
 * @param ocurrido momento del hecho
 * @param origen   servicio que publica; debe coincidir con el exchange ({@code <origen>.eventos})
 * @param traza    traceId W3C, si lo había
 * @param datos    contenido del evento (según {@code contratos/eventos/<origen>.yaml}). Puede llevar datos personales: nunca va a los logs
 */
public record Sobre(UUID id, String tipo, long version, Instant ocurrido, String origen, String traza, JsonNode datos) {

    /** Sin {@code datos}: lo único que se puede imprimir (Ley 1581). */
    @Override
    public String toString() {
        return "Sobre[id=" + id + ", tipo=" + tipo + ", version=" + version + ", origen=" + origen + "]";
    }
}
