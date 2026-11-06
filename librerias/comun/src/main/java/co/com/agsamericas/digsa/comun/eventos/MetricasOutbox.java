package co.com.agsamericas.digsa.comun.eventos;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Métricas del outbox para la alerta de Grafana (I9). Las dos salen de una consulta sobre el índice parcial de pendientes, en cada
 * lectura de Prometheus:
 * <ul>
 *   <li>{@code lume.outbox.pendientes}: eventos sin publicar;</li>
 *   <li>{@code lume.outbox.antiguedad}: segundos desde que se creó el pendiente más viejo (0 si no hay ninguno).</li>
 * </ul>
 * Un broker caído hace crecer ambas. Si la consulta falla, devuelven NaN.
 */
public class MetricasOutbox implements MeterBinder {

    private static final Logger LOG = LoggerFactory.getLogger(MetricasOutbox.class);

    private final JdbcClient jdbc;
    private final Clock reloj;

    public MetricasOutbox(JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Override
    public void bindTo(MeterRegistry registro) {
        Gauge.builder("lume.outbox.pendientes", this, MetricasOutbox::pendientes)
                .description("Eventos del outbox sin publicar").register(registro);
        Gauge.builder("lume.outbox.antiguedad", this, MetricasOutbox::antiguedad).baseUnit("seconds")
                .description("Antigüedad del evento pendiente más viejo del outbox").register(registro);
    }

    double pendientes() {
        try {
            return jdbc.sql("SELECT count(*) FROM outbox WHERE publicado IS NULL").query(Long.class).single();
        } catch (RuntimeException e) {
            LOG.debug("No se pudo leer la métrica de pendientes ({})", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }

    double antiguedad() {
        try {
            Timestamp mas = jdbc.sql("SELECT min(creado) FROM outbox WHERE publicado IS NULL").query(Timestamp.class).single();
            return mas == null ? 0 : Math.max(0, Duration.between(mas.toInstant(), reloj.instant()).toMillis() / 1000.0);
        } catch (RuntimeException e) {
            LOG.debug("No se pudo leer la métrica de antigüedad ({})", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
