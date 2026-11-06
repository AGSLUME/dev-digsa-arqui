package co.com.agsamericas.digsa.comun.eventos;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumo idempotente de eventos de otro servicio (ADR-006), para las colas declaradas con {@link Topologia#consumidor}:
 * <ul>
 *   <li>valida el sobre y que su {@code origen} sea el del exchange por el que llegó (un servicio no publica en nombre de otro);</li>
 *   <li>aplica cada evento una sola vez ({@code mensajes_procesados}, en la misma transacción que la acción);</li>
 *   <li>si la acción falla, el mensaje va a {@code <cola>.reintento} y vuelve tras la espera; agotados los reintentos, o si el mensaje no
 *       es válido, va a {@code <cola>.dlq}.</li>
 * </ul>
 * La regla de versión (descartar la versión menor o igual a la ya aplicada) es de cada copia: la acción la aplica sobre su tabla.
 * Nunca escribe los datos en los logs.
 */
public class ConsumoEventos {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumoEventos.class);
    static final String MOTIVO = "x-lume-motivo";
    /** Tope del cuerpo antes de parsearlo: una foto de usuario pesa menos de 5 KiB. */
    static final int TAMANO_MAXIMO = 256 * 1024;
    private static final long CONFIRMACION_DLQ_MS = 5_000;
    /** Rechazado por la acción, o devuelto por el broker al pasar el límite de entregas de la cola quorum. */
    private static final Set<String> MUERTES_CONTADAS = Set.of("rejected", "delivery_limit");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final int reintentos;
    private final Clock reloj;

    public ConsumoEventos(JdbcClient jdbc, TransactionTemplate tx, RabbitTemplate rabbit, JsonMapper json, int reintentos, Clock reloj) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        this.json = json;
        this.reintentos = reintentos;
        this.reloj = reloj;
    }

    /**
     * Lee y valida el sobre. Tras un reintento, el mensaje llega por el exchange de mensajes muertos del consumidor ({@code <cola>.dlx},
     * que solo él puede escribir); entonces cuenta el exchange por el que entró la primera vez ({@code x-first-death-exchange}, que pone
     * el broker). Un mensaje que llega por cualquier otro exchange no se acepta, aunque traiga ese encabezado.
     *
     * @throws EventoInvalido si no es un sobre válido o el exchange no corresponde a su origen
     */
    public Sobre leer(Message m, String exchangeEsperado) {
        return leer(m, m.getMessageProperties().getConsumerQueue(), exchangeEsperado);
    }

    /** {@link #leer(Message, String)} con la cola de la que se leyó el mensaje. */
    public Sobre leer(Message m, String cola, String exchangeEsperado) {
        MessageProperties p = m.getMessageProperties();
        String exchange = p.getReceivedExchange();
        if (exchange != null && cola != null && exchange.equals(cola + ".dlx")) {
            Object primero = p.getHeaders().get("x-first-death-exchange");
            exchange = primero == null ? null : primero.toString();
        }
        if (exchangeEsperado == null || !exchangeEsperado.equals(exchange)) {
            throw new EventoInvalido("El mensaje no llegó por el exchange esperado.");
        }
        if (m.getBody() == null || m.getBody().length > TAMANO_MAXIMO) {
            throw new EventoInvalido("El cuerpo falta o supera " + TAMANO_MAXIMO + " bytes.");
        }
        JsonNode n;
        try {
            n = json.readTree(m.getBody());
        } catch (JacksonException e) {
            throw new EventoInvalido("El cuerpo no es JSON.");
        }
        if (n == null || !n.isObject()) {
            throw new EventoInvalido("El cuerpo no es un sobre.");
        }
        String tipo = texto(n, "tipo");
        if (tipo.length() > 100 || !OutboxJdbc.TIPO.matcher(tipo).matches()) {
            throw new EventoInvalido("Tipo de evento no válido.");
        }
        String origen = texto(n, "origen");
        if (!exchangeEsperado.equals(origen + ".eventos")) {
            throw new EventoInvalido("El origen del evento no corresponde al exchange.");
        }
        JsonNode version = n.get("version");
        if (version == null || !version.isIntegralNumber()) {
            throw new EventoInvalido("Falta la versión.");
        }
        JsonNode datos = n.get("datos");
        if (datos == null || !datos.isObject()) {
            throw new EventoInvalido("Faltan los datos (un objeto).");
        }
        JsonNode traza = n.get("traza");
        try {
            return new Sobre(UUID.fromString(texto(n, "id")), tipo, version.longValue(), Instant.parse(texto(n, "ocurrido")),
                    origen, traza == null || traza.isNull() ? null : traza.asString(), datos);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new EventoInvalido("Id u ocurrido no válidos.");
        }
    }

    /** Marca el evento como procesado. Exige una transacción activa: la misma de la acción. @return true si es la primera vez */
    public boolean primeraVez(UUID id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("primeraVez va en la misma transacción que la acción.");
        }
        return jdbc.sql("INSERT INTO mensajes_procesados (id, procesado) VALUES (:id, :ahora) ON CONFLICT (id) DO NOTHING")
                .param("id", id).param("ahora", reloj.instant().atOffset(ZoneOffset.UTC)).update() == 1;
    }

    /** {@link #procesar(Message, String, String, Consumer)} con la cola de la que llegó el mensaje (contenedor de escucha). */
    public void procesar(Message m, String exchangeEsperado, Consumer<Sobre> accion) {
        String cola = m.getMessageProperties().getConsumerQueue();
        if (cola == null) {
            throw new IllegalStateException("Sin cola de origen: use procesar(m, cola, exchange, accion).");
        }
        procesar(m, cola, exchangeEsperado, accion);
    }

    /**
     * Aplica la acción una sola vez por evento, en una transacción con la marca de procesado.
     *
     * @throws AmqpRejectAndDontRequeueException si la acción falló y quedan reintentos (el mensaje pasa a {@code <cola>.reintento})
     */
    public void procesar(Message m, String cola, String exchangeEsperado, Consumer<Sobre> accion) {
        Sobre s;
        try {
            s = leer(m, cola, exchangeEsperado);
        } catch (EventoInvalido e) {
            aDlq(m, cola, e);
            return;
        }
        try {
            tx.executeWithoutResult(estado -> {
                if (primeraVez(s.id())) {
                    accion.accept(s);
                }
            });
        } catch (RuntimeException e) {
            // Base caída o sin conexiones: el evento es válido; sigue en el ciclo de reintento sin tope, nunca a la DLQ por eso
            if (!transitorio(e) && rechazos(m, cola) >= reintentos) {
                aDlq(m, cola, e);
                return;
            }
            LOG.atWarn().addKeyValue("evento", s.id()).addKeyValue("tipo", s.tipo()).addKeyValue("cola", cola)
                    .log("Falló el evento {} ({}) en {}; se reintenta ({})", s.id(), s.tipo(), cola, e.getClass().getSimpleName());
            // Sin la causa: la excepción de la acción puede traer la fila con datos personales (p. ej. «Failing row contains»), y el
            // contenedor la registraría completa. Solo la clase.
            throw new AmqpRejectAndDontRequeueException("Falló el evento " + s.id() + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** ¿Un fallo de infraestructura (base caída, sin conexión, bloqueo vencido) y no del evento? */
    static boolean transitorio(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException || t instanceof RecoverableDataAccessException) {
                return true;
            }
        }
        return false;
    }

    /** Veces que el mensaje ya fue rechazado por esta cola (header {@code x-death}). */
    static long rechazos(Message m, String cola) {
        List<Map<String, ?>> muertes = m.getMessageProperties().getXDeathHeader();
        if (muertes == null) {
            return 0;
        }
        long total = 0;
        for (Map<String, ?> d : muertes) {
            if (cola.equals(String.valueOf(d.get("queue"))) && MUERTES_CONTADAS.contains(String.valueOf(d.get("reason")))
                    && d.get("count") instanceof Number c) {
                total += c.longValue();
            }
        }
        return total;
    }

    private void aDlq(Message m, String cola, RuntimeException e) {
        m.getMessageProperties().setHeader(MOTIVO, e.getClass().getSimpleName());
        // Por el exchange de mensajes muertos del propio consumidor: el usuario del servicio no puede escribir en el exchange por defecto.
        // Con confirmación del broker: solo entonces se reconoce el original. Si no se pudo, el original pasa al reintento (sin causa
        // en la excepción) y se vuelve a intentar tras la espera; nunca se devuelve a la cola de inmediato (bucle sin fin).
        try {
            if (rabbit.getConnectionFactory().isSimplePublisherConfirms()) {
                rabbit.invoke(ops -> {
                    ops.send(cola + ".dlx", Topologia.A_DLQ, m);
                    ops.waitForConfirmsOrDie(CONFIRMACION_DLQ_MS);
                    return null;
                });
            } else {
                rabbit.send(cola + ".dlx", Topologia.A_DLQ, m);
            }
        } catch (RuntimeException fallo) {
            LOG.warn("No se pudo enviar el mensaje {} a {}.dlq ({}); pasa al reintento", m.getMessageProperties().getMessageId(), cola,
                    fallo.getClass().getSimpleName());
            throw new AmqpRejectAndDontRequeueException("No se pudo enviar a la DLQ (" + fallo.getClass().getSimpleName() + ")");
        }
        LOG.atWarn().addKeyValue("mensaje", m.getMessageProperties().getMessageId()).addKeyValue("cola", cola)
                .log("Mensaje {} enviado a {}.dlq ({}: {})", m.getMessageProperties().getMessageId(), cola, e.getClass().getSimpleName(), e instanceof EventoInvalido ? e.getMessage() : "agotó los reintentos");
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new EventoInvalido("Falta el campo " + campo + ".");
        }
        return v.asString();
    }
}
