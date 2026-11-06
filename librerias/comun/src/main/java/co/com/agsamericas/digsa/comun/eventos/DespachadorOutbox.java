package co.com.agsamericas.digsa.comun.eventos;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lleva los eventos del outbox a RabbitMQ (ADR-006). Cada pasada toma un lote con {@code FOR UPDATE SKIP LOCKED} (varias instancias no
 * publican la misma fila), publica cada evento en {@code <origen>.eventos} con routing key = tipo, espera la confirmación del broker y lo
 * marca publicado. Si un envío falla, la fila queda pendiente con espera creciente (2, 4, 8 ... hasta 300 s) y se corta la pasada: ningún
 * evento se descarta. Entrega «al menos una vez»: si la marca falla tras publicar, el evento sale de nuevo y el consumidor lo ignora por
 * su id. Nunca escribe el cuerpo en los logs (Ley 1581).
 */
public class DespachadorOutbox implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(DespachadorOutbox.class);
    private static final long ESPERA_MAXIMA_S = 300;
    private static final int PASADAS_POR_CICLO = 10;
    private static final int LOTE_PURGA = 5_000;
    /** Cuánto dura la reserva de un lote: más que el peor caso de publicarlo (lote × confirmación). */
    static final Duration RESERVA = Duration.ofMinutes(10);

    private record Fila(UUID id, String tipo, String cuerpo, int intentos) {
    }

    private record Reservada(Fila fila, Instant creado) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final PropiedadesEventos props;
    private final Clock reloj;
    private volatile ScheduledExecutorService ciclo;

    public DespachadorOutbox(JdbcClient jdbc, TransactionTemplate tx, RabbitTemplate rabbit, PropiedadesEventos props, Clock reloj) {
        if (!rabbit.getConnectionFactory().isSimplePublisherConfirms()) {
            throw new IllegalStateException("spring.rabbitmq.publisher-confirm-type debe ser simple: el outbox espera la confirmación del broker.");
        }
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        this.props = props;
        this.reloj = reloj;
    }

    /**
     * Una pasada: publica hasta un lote de eventos pendientes. La transacción solo RESERVA el lote (corrida {@code siguiente} hasta
     * {@link #RESERVA}, con {@code SKIP LOCKED}) y se cierra; la publicación al broker va fuera de ella, así un broker lento no retiene
     * conexiones del pool ni bloqueos. Si la instancia muere a mitad, la reserva vence y otra instancia toma las filas.
     *
     * @return cuántos quedaron publicados
     */
    public int despachar() {
        Instant ahora = reloj.instant();
        List<Fila> filas = tx.execute(estado -> jdbc.sql("""
                UPDATE outbox o SET siguiente = :reserva
                FROM (SELECT id FROM outbox
                      WHERE publicado IS NULL AND siguiente <= :ahora
                      ORDER BY creado, id
                      LIMIT :lote
                      FOR UPDATE SKIP LOCKED) l
                WHERE o.id = l.id
                RETURNING o.id, o.tipo, o.cuerpo::text AS cuerpo, o.intentos, o.creado""")
                .param("ahora", utc(ahora)).param("reserva", utc(ahora.plus(RESERVA))).param("lote", props.lote())
                .query((rs, i) -> new Reservada(new Fila(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("cuerpo"),
                        rs.getInt("intentos")), rs.getTimestamp("creado").toInstant()))
                .list().stream().sorted(Comparator.comparing(Reservada::creado).thenComparing(r -> r.fila().id())).map(Reservada::fila).toList());
        if (filas == null || filas.isEmpty()) {
            return 0;
        }
        int publicados = 0;
        for (int i = 0; i < filas.size(); i++) {
            Fila f = filas.get(i);
            try {
                enviar(f);
            } catch (RuntimeException e) {
                fallo(f, e, reloj.instant());
                liberar(filas.subList(i + 1, filas.size()));
                break;
            }
            jdbc.sql("UPDATE outbox SET publicado = :ahora, error = NULL WHERE id = :id")
                    .param("ahora", utc(reloj.instant())).param("id", f.id()).update();
            publicados++;
        }
        return publicados;
    }

    /** Las reservadas que no se intentaron (el broker falló antes): quedan disponibles ya, sin sumar intento. */
    private void liberar(List<Fila> filas) {
        if (!filas.isEmpty()) {
            jdbc.sql("UPDATE outbox SET siguiente = :ahora WHERE id = ANY(:ids) AND publicado IS NULL")
                    .param("ahora", utc(reloj.instant())).param("ids", filas.stream().map(Fila::id).toArray(UUID[]::new)).update();
        }
    }

    /** Borra, por lotes, lo ya publicado y los ids procesados más viejos que la retención. @return filas borradas */
    public int purgar() {
        Instant ahora = reloj.instant();
        int total = 0;
        int n;
        do {
            n = jdbc.sql("""
                    DELETE FROM outbox WHERE id IN (
                      SELECT id FROM outbox WHERE publicado IS NOT NULL AND publicado < :limite LIMIT :lote)""")
                    .param("limite", utc(ahora.minus(Duration.ofDays(props.retenerDias())))).param("lote", LOTE_PURGA).update();
            total += n;
        } while (n == LOTE_PURGA);
        do {
            n = jdbc.sql("""
                    DELETE FROM mensajes_procesados WHERE id IN (
                      SELECT id FROM mensajes_procesados WHERE procesado < :limite LIMIT :lote)""")
                    .param("limite", utc(ahora.minus(Duration.ofDays(props.retenerProcesadosDias())))).param("lote", LOTE_PURGA).update();
            total += n;
        } while (n == LOTE_PURGA);
        return total;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private void enviar(Fila f) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setContentEncoding(StandardCharsets.UTF_8.name());
        p.setMessageId(f.id().toString());
        p.setType(f.tipo());
        p.setAppId(props.origen());
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message m = new Message(f.cuerpo().getBytes(StandardCharsets.UTF_8), p);
        rabbit.invoke(ops -> {
            ops.send(props.exchange(), f.tipo(), m);
            ops.waitForConfirmsOrDie(props.confirmacionMs());
            return null;
        });
    }

    private void fallo(Fila f, RuntimeException e, Instant ahora) {
        int intentos = f.intentos() + 1;
        long espera = Math.min(1L << Math.min(intentos, 9), ESPERA_MAXIMA_S);
        // Solo la clase (y la de su causa): los mensajes del broker o de la base pueden traer usuarios, colas o filas
        String error = e.getClass().getSimpleName() + (e.getCause() == null ? "" : " / " + e.getCause().getClass().getSimpleName());
        jdbc.sql("UPDATE outbox SET intentos = :intentos, siguiente = :siguiente, error = :error WHERE id = :id")
                .param("intentos", intentos).param("siguiente", utc(ahora.plusSeconds(espera)))
                .param("error", error.length() > 300 ? error.substring(0, 300) : error).param("id", f.id()).update();
        LOG.atWarn().addKeyValue("evento", f.id()).addKeyValue("tipo", f.tipo()).addKeyValue("intentos", intentos)
                .log("No se pudo publicar el evento {} ({}, intento {}); se reintenta en {} s ({})", f.id(), f.tipo(), intentos, espera,
                        e.getClass().getSimpleName());
    }

    private void ciclo() {
        try {
            int pasadas = 0;
            // Lote lleno: puede haber más pendientes; se corta si se pidió detener
            while (ciclo != null && !Thread.currentThread().isInterrupted() && despachar() == props.lote() && ++pasadas < PASADAS_POR_CICLO) {
                Thread.onSpinWait();
            }
        } catch (Throwable e) {
            // Throwable: un Error no capturado cancelaría el ciclo programado para siempre, sin aviso
            LOG.warn("Falló la pasada del despachador del outbox ({})", e.getClass().getSimpleName());
        }
    }

    private void purgaSegura() {
        try {
            purgar();
        } catch (Throwable e) {
            LOG.warn("Falló la purga del outbox ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void start() {
        if (props.cadaMs() == 0 || ciclo != null) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("lume-outbox").daemon(true).factory());
        s.scheduleWithFixedDelay(this::ciclo, props.cadaMs(), props.cadaMs(), TimeUnit.MILLISECONDS);
        s.scheduleWithFixedDelay(this::purgaSegura, 1, 60, TimeUnit.MINUTES);
        ciclo = s;
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService s = ciclo;
        ciclo = null;
        if (s == null) {
            return;
        }
        s.shutdown();
        try {
            if (!s.awaitTermination(10, TimeUnit.SECONDS)) {
                s.shutdownNow();
            }
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return ciclo != null;
    }
}
