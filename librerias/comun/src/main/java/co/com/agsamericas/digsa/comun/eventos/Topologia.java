package co.com.agsamericas.digsa.comun.eventos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Topología de un consumidor (ADR-006), con los permisos de {@code infra/rabbitmq/usuarios.sh}: el servicio X solo configura y escribe
 * recursos {@code X.*} y solo lee sus colas y los {@code *.eventos}. Por eso:
 * <ul>
 *   <li>el exchange del productor NO se declara aquí (lo declara solo su dueño); solo se enlaza la cola a él. Si al arrancar aún no
 *       existe, {@link EnlazadorEventos} reintenta el enlace;</li>
 *   <li>todo lo del consumidor lleva el prefijo del servicio: {@code <servicio>.<nombre>}, su {@code .reintento}, su {@code .dlq} y su
 *       exchange de mensajes muertos {@code .dlx} (el exchange por defecto no se puede usar: no hay permiso de escritura en él).</li>
 * </ul>
 * Colas quorum, reintento diferido con TTL + dead-letter, sin plugins.
 */
public final class Topologia {

    static final String A_REINTENTO = "reintento";
    static final String A_PRINCIPAL = "principal";
    static final String A_DLQ = "dlq";
    /** Servicios y exchanges como el permiso de lectura de infra/rabbitmq/usuarios.sh: {@code [a-z-]+.eventos}. */
    private static final Pattern SERVICIO = Pattern.compile("[a-z][a-z-]{1,40}");
    private static final Pattern NOMBRE = Pattern.compile("[a-z][a-z0-9_-]{1,40}");
    private static final Pattern EXCHANGE = Pattern.compile("[a-z][a-z-]{1,40}\\.eventos");

    private Topologia() {
    }

    /**
     * Declara, para el consumidor {@code <servicio>.<nombre>}:
     * <ul>
     *   <li>{@code <cola>}: quorum, enlazada a {@code exchange} con cada clave; lo rechazado va a {@code <cola>.reintento};</li>
     *   <li>{@code <cola>.reintento}: quorum, sin consumidores; tras {@code espera} devuelve el mensaje a {@code <cola>};</li>
     *   <li>{@code <cola>.dlq}: quorum, para revisión manual (inválidos o reintentos agotados, ver {@link ConsumoEventos});</li>
     *   <li>{@code <cola>.dlx}: exchange directo propio que lleva los mensajes entre las tres colas.</li>
     * </ul>
     *
     * @param servicio el servicio consumidor (el usuario de RabbitMQ), p. ej. {@code visitas}
     * @param nombre   el nombre del consumidor dentro del servicio, p. ej. {@code usuarios}
     * @param exchange el exchange del productor, p. ej. {@code seguridad.eventos}
     */
    public static Declarables consumidor(String servicio, String nombre, String exchange, Duration espera, String... claves) {
        if (servicio == null || !SERVICIO.matcher(servicio).matches() || nombre == null || !NOMBRE.matcher(nombre).matches()) {
            throw new IllegalArgumentException("Servicio y nombre del consumidor: minúsculas, números, guion o guion bajo.");
        }
        if (exchange == null || !EXCHANGE.matcher(exchange).matches()) {
            throw new IllegalArgumentException("El exchange del productor es <servicio>.eventos.");
        }
        if (exchange.equals(servicio + ".eventos")) {
            throw new IllegalArgumentException("Un servicio no consume su propio exchange.");
        }
        if (claves.length == 0) {
            throw new IllegalArgumentException("El consumidor necesita al menos una clave.");
        }
        String cola = cola(servicio, nombre);
        DirectExchange dlx = ExchangeBuilder.directExchange(cola + ".dlx").durable(true).build();
        Queue principal = QueueBuilder.durable(cola).quorum().deadLetterExchange(dlx.getName()).deadLetterRoutingKey(A_REINTENTO).build();
        Queue reintento = QueueBuilder.durable(cola + ".reintento").quorum()
                .ttl(Math.toIntExact(espera.toMillis())).deadLetterExchange(dlx.getName()).deadLetterRoutingKey(A_PRINCIPAL).build();
        Queue dlq = QueueBuilder.durable(cola + ".dlq").quorum().build();
        List<Declarable> d = new ArrayList<>(List.of(dlx, principal, reintento, dlq,
                BindingBuilder.bind(reintento).to(dlx).with(A_REINTENTO),
                BindingBuilder.bind(principal).to(dlx).with(A_PRINCIPAL),
                BindingBuilder.bind(dlq).to(dlx).with(A_DLQ)));
        for (String clave : claves) {
            // Al exchange del productor, sin declararlo: si aún no existe, el fallo no tumba la conexión y EnlazadorEventos reintenta
            Binding b = new Binding(cola, Binding.DestinationType.QUEUE, exchange, clave, null);
            b.setIgnoreDeclarationExceptions(true);
            d.add(b);
        }
        return new Declarables(d);
    }

    /** El nombre de la cola principal: {@code <servicio>.<nombre>}. */
    public static String cola(String servicio, String nombre) {
        return servicio + "." + nombre;
    }

    /** ¿Es un enlace a un exchange de eventos ajeno (el que puede faltar al arrancar)? */
    static boolean enlaceAjeno(Binding b) {
        return b.isDestinationQueue() && EXCHANGE.matcher(b.getExchange()).matches();
    }
}
