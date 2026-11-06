package co.com.agsamericas.digsa.comun.eventos;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * Un consumidor del servicio {@code visitas} (usuario de RabbitMQ {@code visitas}): declara {@link Topologia#consumidor} con su
 * {@link RabbitAdmin} y, si se le pide, escucha su cola llamando a {@link ConsumoEventos#procesar}. Los enlaces a exchanges ajenos
 * que fallen se ignoran, como hace RabbitAdmin con {@code ignoreDeclarationExceptions}.
 */
final class ConsumidorVisitas implements AutoCloseable {

    final String nombre = "prueba-" + UUID.randomUUID().toString().substring(0, 8);
    final String cola = Topologia.cola("visitas", nombre);
    final Declarables topologia;
    final AtomicInteger llamadas = new AtomicInteger();
    final List<UUID> procesados = new CopyOnWriteArrayList<>();
    private final RabbitAdmin admin;
    private SimpleMessageListenerContainer contenedor;

    ConsumidorVisitas(RabbitAdmin admin, String exchangeProductor, Duration espera, String... claves) {
        this.admin = admin;
        this.topologia = Topologia.consumidor("visitas", nombre, exchangeProductor, espera, claves.length == 0 ? new String[]{"usuario.#"} : claves);
        declarar(admin, topologia);
    }

    /** Declara lo que declara RabbitAdmin al conectar: exchanges, colas y enlaces (ignorando los enlaces marcados si fallan). */
    static void declarar(RabbitAdmin admin, Declarables d) {
        for (Declarable x : d.getDeclarables()) {
            if (x instanceof Exchange e) {
                admin.declareExchange(e);
            }
        }
        for (Declarable x : d.getDeclarables()) {
            if (x instanceof Queue q) {
                admin.declareQueue(q);
            }
        }
        for (Declarable x : d.getDeclarables()) {
            if (x instanceof Binding b) {
                try {
                    admin.declareBinding(b);
                } catch (RuntimeException e) {
                    if (!b.isIgnoreDeclarationExceptions()) {
                        throw e;
                    }
                }
            }
        }
    }

    String dlq() {
        return cola + ".dlq";
    }

    String reintento() {
        return cola + ".reintento";
    }

    String dlx() {
        return cola + ".dlx";
    }

    /** Empieza a consumir la cola con {@code consumo.procesar(m, exchangeEsperado, accion)}. */
    void escuchar(ConsumoEventos consumo, String exchangeEsperado, Consumer<Sobre> accion) {
        contenedor = new SimpleMessageListenerContainer(InfraEventos.VISITAS);
        contenedor.setQueueNames(cola);
        contenedor.setPrefetchCount(1);
        contenedor.setDefaultRequeueRejected(false);
        contenedor.setMessageListener((MessageListener) m -> consumo.procesar(m, exchangeEsperado, s -> {
            llamadas.incrementAndGet();
            accion.accept(s);
            procesados.add(s.id());
        }));
        contenedor.afterPropertiesSet();
        contenedor.start();
    }

    @Override
    public void close() {
        if (contenedor != null) {
            contenedor.stop();
            contenedor.destroy();
        }
        admin.deleteQueue(cola);
        admin.deleteQueue(reintento());
        admin.deleteQueue(dlq());
        admin.deleteExchange(dlx());
    }
}
