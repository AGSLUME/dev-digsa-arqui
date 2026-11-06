package co.com.agsamericas.digsa.comun.auditoria;

import co.com.agsamericas.digsa.reglas.Bitacora;
import java.time.Instant;
import java.util.Objects;

/**
 * Una fila de la bitácora (Anexo 5, prueba 15), tal como la publica cualquier servicio hacia {@code seguridad} (ADR-002, ADR-006).
 *
 * @param ocurrido  momento del hecho
 * @param servicio  servicio que lo publica (lo valida el consumidor contra la conexión de RabbitMQ)
 * @param uid       id numérico del usuario (el mismo del monolito), o {@code null} si no hay sesión (p. ej. un ingreso fallido)
 * @param usuario   nombre de usuario; nunca nombre completo ni documento
 * @param operacion una de las 6 operaciones del Anexo 5
 * @param tipo      tipo de evento de {@link Bitacora#TIPOS} o propio del servicio
 * @param detalle   solo identificadores y filtros (lista blanca): nunca datos personales ni secretos (Ley 1581)
 * @param ip        IP del cliente según el gateway
 * @param traza     traceId W3C para correlacionar con los logs y Zipkin
 */
public record EventoAuditoria(Instant ocurrido, String servicio, Long uid, String usuario, Bitacora.Operacion operacion, String tipo,
                              String detalle, String ip, String traza) {

    public EventoAuditoria {
        Objects.requireNonNull(ocurrido, "ocurrido");
        Objects.requireNonNull(servicio, "servicio");
        Objects.requireNonNull(operacion, "operacion");
        Objects.requireNonNull(tipo, "tipo");
    }
}
