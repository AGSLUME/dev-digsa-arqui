package co.com.agsamericas.digsa.comun.eventos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de los eventos ({@code lume.eventos.*}). Sin {@code origen} no se activa nada.
 *
 * @param origen                nombre del servicio que publica (p. ej. {@code seguridad})
 * @param exchange              exchange topic propio; por defecto {@code <origen>.eventos}
 * @param cadaMs                intervalo del despachador; 0 lo apaga (pruebas)
 * @param lote                  filas por pasada del despachador
 * @param confirmacionMs        espera máxima de la confirmación del broker por mensaje
 * @param retenerDias           días que se conservan los eventos ya publicados
 * @param retenerProcesadosDias días que se conservan los ids de {@code mensajes_procesados}
 * @param reintentos            reintentos diferidos de un consumidor antes de mandar el mensaje a la DLQ
 * @param reenlazarMs           cada cuánto se reintenta enlazar una cola a un exchange ajeno que aún no existe
 */
@ConfigurationProperties("lume.eventos")
public record PropiedadesEventos(String origen, String exchange, @DefaultValue("1000") long cadaMs, @DefaultValue("100") int lote,
                                 @DefaultValue("5000") long confirmacionMs, @DefaultValue("7") int retenerDias,
                                 @DefaultValue("30") int retenerProcesadosDias, @DefaultValue("5") int reintentos,
                                 @DefaultValue("30000") long reenlazarMs) {

    public PropiedadesEventos {
        if (origen == null || !origen.matches("[a-z][a-z-]{1,40}")) {
            throw new IllegalArgumentException("lume.eventos.origen debe ser el nombre del servicio en minúsculas.");
        }
        if (exchange == null || exchange.isBlank()) {
            exchange = origen + ".eventos";
        }
        if (cadaMs < 0 || lote < 1 || confirmacionMs < 1 || retenerDias < 1 || retenerProcesadosDias < 1 || reintentos < 0 || reenlazarMs < 1) {
            throw new IllegalArgumentException("Valores de lume.eventos.* fuera de rango.");
        }
    }
}
