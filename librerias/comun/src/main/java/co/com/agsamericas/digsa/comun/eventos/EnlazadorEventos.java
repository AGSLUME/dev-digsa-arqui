package co.com.agsamericas.digsa.comun.eventos;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;

/**
 * Enlaza las colas de los consumidores a los exchanges de eventos ajenos ({@link Topologia#consumidor}). El exchange lo declara solo su
 * dueño: si al arrancar todavía no existe, el enlace falla (404) sin tumbar la conexión, y aquí se reintenta cada
 * {@code lume.eventos.reenlazar-ms} hasta que exista. Cuando todos quedan enlazados, el ciclo se detiene.
 */
public class EnlazadorEventos implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(EnlazadorEventos.class);

    private final RabbitTemplate rabbit;
    private final Set<Binding> pendientes = ConcurrentHashMap.newKeySet();
    private final long cadaMs;
    private volatile ScheduledExecutorService ciclo;

    public EnlazadorEventos(RabbitTemplate rabbit, List<Binding> enlaces, long cadaMs) {
        this.rabbit = rabbit;
        enlaces.stream().filter(Topologia::enlaceAjeno).forEach(pendientes::add);
        this.cadaMs = cadaMs;
    }

    /** Intenta los enlaces pendientes. @return cuántos siguen pendientes */
    public int enlazar() {
        for (Binding b : List.copyOf(pendientes)) {
            try {
                rabbit.execute(canal -> canal.queueBind(b.getDestination(), b.getExchange(), b.getRoutingKey()));
                pendientes.remove(b);
            } catch (RuntimeException e) {
                LOG.warn("No se pudo enlazar {} a {} ({}: {}): se reintenta", b.getDestination(), b.getExchange(), e.getClass().getSimpleName(),
                        causaRaiz(e));
            }
        }
        return pendientes.size();
    }

    /** El texto del broker (p. ej. «NOT_FOUND - no exchange ...» o «ACCESS_REFUSED ...»): distingue un exchange que falta de un permiso. */
    private static String causaRaiz(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = String.valueOf(t.getMessage()).replaceAll("[\r\n]", " ");
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private void cicloSeguro() {
        try {
            if (enlazar() == 0) {
                ScheduledExecutorService s = ciclo;
                if (s != null) {
                    s.shutdown();
                }
            }
        } catch (Throwable e) {
            LOG.warn("Falló el ciclo de enlaces ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void start() {
        if (ciclo != null || pendientes.isEmpty()) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("lume-enlaces").daemon(true).factory());
        s.scheduleWithFixedDelay(this::cicloSeguro, 0, cadaMs, TimeUnit.MILLISECONDS);
        ciclo = s;
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService s = ciclo;
        ciclo = null;
        if (s != null) {
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return ciclo != null;
    }
}
