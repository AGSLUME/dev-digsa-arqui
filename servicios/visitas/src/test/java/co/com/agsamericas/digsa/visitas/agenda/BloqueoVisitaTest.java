package co.com.agsamericas.digsa.visitas.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VA1.6 (revisión de BD M1): {@code bloquear(id)} usa {@code FOR NO KEY UPDATE}. Con la visita bloqueada en una transacción, el INSERT de una
 * fila hija (visitas_historial, que toma KEY SHARE sobre la visita) desde otra transacción NO espera; un segundo {@code bloquear(id)} sí
 * espera hasta que la primera transacción termina. Hilos reales y transacciones reales (la base es PostgreSQL de Testcontainers).
 */
class BloqueoVisitaTest extends FixturaAgenda {

    @Autowired
    PlatformTransactionManager tm;

    @Test
    @DisplayName("el INSERT en el historial de la visita bloqueada no espera (< 2 s); un segundo bloquear sí espera a que termine la primera")
    void bloquearNoChocaConHijasPeroSiConOtroBloquear() throws Exception {
        long lider = sembrarAuditor("Líder bloqueo", true);
        long visita = sembrarVisita("anual", sembrarPunto("EJC", null), lider, LocalDate.of(2033, 2, 8));
        TransactionTemplate tx = new TransactionTemplate(tm);
        ExecutorService hilos = Executors.newFixedThreadPool(3);
        CountDownLatch bloqueada = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            // A: bloquea y retiene la transacción abierta
            Future<Boolean> a = hilos.submit(() -> tx.execute(s -> {
                boolean hay = repo.bloquear(visita).isPresent();
                bloqueada.countDown();
                try {
                    if (!soltar.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("la prueba no soltó el bloqueo");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return hay;
            }));
            assertThat(bloqueada.await(10, TimeUnit.SECONDS)).as("A tomó el bloqueo").isTrue();

            // B: INSERT en la tabla hija desde otra transacción: no debe esperar
            long t0 = System.nanoTime();
            Future<?> b = hilos.submit(() -> tx.executeWithoutResult(s -> repo.historial(visita, lider, "u" + lider, "evento.concurrente", "detalle")));
            b.get(2, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("el INSERT hijo no espera al bloqueo").isLessThan(Duration.ofSeconds(2));
            assertThat(repo.historialDe(visita)).extracting(f -> f.get("evento")).contains("evento.concurrente");

            // C: otro bloquear de la misma visita: espera
            Future<Boolean> c = hilos.submit(() -> tx.execute(s -> repo.bloquear(visita).isPresent()));
            assertThatThrownBy(() -> c.get(1500, TimeUnit.MILLISECONDS)).as("C espera mientras A retiene").isInstanceOf(TimeoutException.class);
            assertThat(c.isDone()).isFalse();

            // A termina: C avanza
            soltar.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(c.get(10, TimeUnit.SECONDS)).as("C bloqueó al terminar A").isTrue();
        } finally {
            soltar.countDown();
            hilos.shutdownNow();
        }
    }

    @Test
    @DisplayName("bloquear de una visita inexistente devuelve vacío y no bloquea nada")
    void inexistente() {
        java.util.Optional<RepositorioVisitas.Visita> r = new TransactionTemplate(tm).execute(s -> repo.bloquear(-1L));
        assertThat(r).isEmpty();
    }
}
