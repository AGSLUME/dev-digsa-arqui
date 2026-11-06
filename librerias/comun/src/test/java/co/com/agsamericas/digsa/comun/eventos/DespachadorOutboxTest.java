package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;

/** Pruebas 5 a 8 del diseño S2-OUTBOX: el despachador (publicación con confirmación, broker caído, concurrencia, purga). */
class DespachadorOutboxTest extends PruebaEventosBase {

    private static final Map<String, Object> DATOS = Map.of("documento", "99887766", "nombre", "Persona Ficticia Dos");

    @Test
    @DisplayName("5: despachar publica en el exchange con la clave del tipo y las propiedades del mensaje; marca publicado")
    void despacharPublica() {
        String cola = colaEnlazada("usuario.#");
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, DATOS);

        int publicados = despachador.despachar();

        assertThat(publicados).isEqualTo(1);
        Message m = rabbitLector.receive(cola, 5000);
        assertThat(m).as("llegó a la cola enlazada").isNotNull();
        var p = m.getMessageProperties();
        assertThat(p.getReceivedExchange()).isEqualTo(EXCHANGE);
        assertThat(p.getReceivedRoutingKey()).isEqualTo("usuario.creado");
        assertThat(p.getMessageId()).isEqualTo(id.toString());
        assertThat(p.getType()).isEqualTo("usuario.creado");
        assertThat(p.getAppId()).isEqualTo("seguridad");
        assertThat(p.getContentType()).isEqualTo("application/json");
        assertThat(p.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        Sobre sobre = mapper.readValue(m.getBody(), Sobre.class);
        Sobre guardado = mapper.readValue(jdbc.sql("SELECT cuerpo::text FROM outbox WHERE id = :id").param("id", id).query(String.class).single(),
                Sobre.class);
        assertThat(sobre).isEqualTo(guardado);
        assertThat(sobre.id()).isEqualTo(id);
        assertThat(sobre.datos()).isEqualTo(mapper.valueToTree(DATOS));

        var fila = jdbc.sql("SELECT publicado IS NOT NULL AS publicado, intentos, error FROM outbox WHERE id = :id").param("id", id).query().singleRow();
        assertThat(fila.get("publicado")).isEqualTo(true);
        assertThat(fila.get("error")).isNull();
        assertThat(despachador.despachar()).as("ya no queda nada pendiente").isZero();
        assertThat(rabbitLector.receive(cola, 300)).as("no se publica dos veces").isNull();
    }

    @Test
    @DisplayName("5: un evento sin cola enlazada termina en seguridad.sin-ruta (exchange alterno) y no se pierde")
    void sinColaEnlazadaVaASinRuta() {
        adminLector.purgeQueue("seguridad.sin-ruta");
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, DATOS);

        assertThat(despachador.despachar()).isEqualTo(1);

        Message m = rabbitLector.receive("seguridad.sin-ruta", 5000);
        assertThat(m).as("el evento quedó en la cola sin-ruta").isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo("usuario.creado");
        assertThat(jdbc.sql("SELECT publicado IS NOT NULL FROM outbox WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("5: la clave de enrutamiento separa eventos: una cola de cuenta.# no recibe usuario.*")
    void enrutamientoPorClave() {
        String soloCuenta = colaEnlazada("cuenta.#");
        String soloUsuario = colaEnlazada("usuario.#");
        publicarEnTransaccion("usuario.creado", "usuario:1", 1, DATOS);
        publicarEnTransaccion("cuenta.bloqueada", "usuario:1", 2, DATOS);
        assertThat(despachador.despachar()).isEqualTo(2);
        assertThat(leerTodo(soloCuenta, 1000)).extracting(m -> m.getMessageProperties().getType()).containsExactly("cuenta.bloqueada");
        assertThat(leerTodo(soloUsuario, 1000)).extracting(m -> m.getMessageProperties().getType()).containsExactly("usuario.creado");
    }

    @Test
    @DisplayName("5: sin filas pendientes despachar devuelve 0; el orden es por creado")
    void sinPendientesYOrden() {
        assertThat(despachador.despachar()).isZero();
        String cola = colaEnlazada("#");
        for (int i = 1; i <= 5; i++) {
            publicarEnTransaccion("usuario.actualizado", "usuario:1", i, DATOS);
        }
        // Las filas llevan el mismo ahora o uno creciente; fijamos un creado distinto por fila para comprobar el ORDER BY creado
        jdbc.sql("UPDATE outbox SET creado = now() - (6 - version) * interval '1 minute'").update();
        assertThat(despachador.despachar()).isEqualTo(5);
        List<Long> versiones = leerTodo(cola, 1000).stream().map(m -> mapper.readValue(m.getBody(), Sobre.class).version()).toList();
        assertThat(versiones).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("5: lume.eventos.lote limita cuántas filas toma cada llamada (lote=25 en las pruebas)")
    void respetaElLote() {
        colaEnlazada("#");
        tx.executeWithoutResult(s -> {
            for (int i = 1; i <= 60; i++) {
                publicador.publicar("usuario.actualizado", "usuario:" + i, 1, DATOS);
            }
        });
        assertThat(despachador.despachar()).isEqualTo(25);
        assertThat(despachador.despachar()).isEqualTo(25);
        assertThat(despachador.despachar()).isEqualTo(10);
        assertThat(despachador.despachar()).isZero();
    }

    @Test
    @DisplayName("6: con el broker caído la fila sigue pendiente (intentos=1, siguiente futuro, error lleno) y no sale excepción; al reponer se publica")
    void brokerCaido() {
        String cola = colaEnlazada("usuario.#");
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, DATOS);
        Instant antes = Instant.now();

        InfraEventos.PROXY.caer();
        int publicados = despachador.despachar(); // no debe lanzar

        assertThat(publicados).isZero();
        var fila = jdbc.sql("SELECT publicado, intentos, siguiente, error FROM outbox WHERE id = :id").param("id", id).query().singleRow();
        assertThat(fila.get("publicado")).isNull();
        assertThat(((Number) fila.get("intentos")).intValue()).isEqualTo(1);
        assertThat(((java.sql.Timestamp) fila.get("siguiente")).toInstant()).isAfter(antes);
        String error = (String) fila.get("error");
        assertThat(error).isNotBlank();
        assertThat(error.length()).isLessThanOrEqualTo(300);
        assertThat(error).as("clase de la excepción más el mensaje").contains("Exception");
        assertThat(contar("outbox")).as("nunca se descarta un evento").isEqualTo(1);

        // Un segundo intento antes de que venza «siguiente» no toca la fila (retroceso exponencial)
        assertThat(despachador.despachar()).isZero();
        assertThat(jdbc.sql("SELECT intentos FROM outbox WHERE id = :id").param("id", id).query(Integer.class).single()).isEqualTo(1);

        // Se repone el broker y se adelanta «siguiente»
        InfraEventos.PROXY.reponer();
        jdbc.sql("UPDATE outbox SET siguiente = now() - interval '1 second' WHERE id = :id").param("id", id).update();
        assertThat(despachador.despachar()).isEqualTo(1);
        Message m = rabbitLector.receive(cola, 5000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(jdbc.sql("SELECT publicado IS NOT NULL FROM outbox WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("6: con el broker caído cada fila fallida suma un intento y el lote se corta (no se golpea el broker N veces)")
    void brokerCaidoCortaElLote() {
        colaEnlazada("#");
        tx.executeWithoutResult(s -> {
            for (int i = 1; i <= 5; i++) {
                publicador.publicar("usuario.actualizado", "usuario:" + i, 1, DATOS);
            }
        });
        InfraEventos.PROXY.caer();
        assertThat(despachador.despachar()).isZero();
        long conIntento = jdbc.sql("SELECT count(*) FROM outbox WHERE intentos > 0").query(Long.class).single();
        assertThat(conIntento).as("solo la fila que falló lleva intento; el resto se deja para después").isEqualTo(1);
        assertThat(contar("outbox")).isEqualTo(5);
    }

    @Test
    @DisplayName("7: dos hilos despachando 200 filas: cada id se publica exactamente una vez")
    void concurrencia() throws Exception {
        String cola = colaEnlazada("#");
        Set<UUID> esperados = new HashSet<>();
        tx.executeWithoutResult(s -> {
            for (int i = 1; i <= 200; i++) {
                esperados.add(publicador.publicar("usuario.actualizado", "usuario:" + i, 1, DATOS));
            }
        });

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tareas = new ArrayList<>();
            for (int h = 0; h < 2; h++) {
                tareas.add(() -> {
                    int total = 0;
                    int n;
                    while ((n = despachador.despachar()) > 0) {
                        total += n;
                    }
                    return total;
                });
            }
            int suma = 0;
            for (Future<Integer> f : hilos.invokeAll(tareas, 60, TimeUnit.SECONDS)) {
                suma += f.get();
            }
            int resto;
            while ((resto = despachador.despachar()) > 0) {
                suma += resto;
            }
            assertThat(suma).as("entre los dos publican 200 filas, ni más ni menos").isEqualTo(200);
        } finally {
            hilos.shutdownNow();
        }

        List<String> ids = leerTodo(cola, 1500).stream().map(m -> m.getMessageProperties().getMessageId()).toList();
        assertThat(ids).hasSize(200).doesNotHaveDuplicates();
        assertThat(ids).containsExactlyInAnyOrderElementsOf(esperados.stream().map(UUID::toString).toList());
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE publicado IS NULL").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("8: purgar borra solo lo publicado y viejo (outbox > 7 días; mensajes_procesados > 30 días)")
    void purgar() {
        UUID viejoPublicado = insertarOutbox("now() - interval '8 days'", "now() - interval '8 days'");
        UUID recientePublicado = insertarOutbox("now() - interval '1 day'", "now() - interval '1 day'");
        UUID viejoPendiente = insertarOutbox("now() - interval '40 days'", null);
        UUID recienteHoy = insertarOutbox("now()", null);
        UUID procesadoViejo = UUID.randomUUID();
        UUID procesadoReciente = UUID.randomUUID();
        jdbc.sql("INSERT INTO mensajes_procesados (id, procesado) VALUES (:a, now() - interval '31 days'), (:b, now() - interval '1 day')")
                .param("a", procesadoViejo).param("b", procesadoReciente).update();

        int borradas = despachador.purgar();

        assertThat(borradas).as("1 del outbox + 1 de mensajes_procesados").isEqualTo(2);
        assertThat(jdbc.sql("SELECT id FROM outbox").query(UUID.class).list())
                .containsExactlyInAnyOrder(recientePublicado, viejoPendiente, recienteHoy).doesNotContain(viejoPublicado);
        assertThat(jdbc.sql("SELECT id FROM mensajes_procesados").query(UUID.class).list()).containsExactly(procesadoReciente);
        assertThat(despachador.purgar()).as("idempotente").isZero();
    }

    private UUID insertarOutbox(String creado, String publicado) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO outbox (id, tipo, agregado, version, cuerpo, creado, siguiente, publicado) VALUES "
                + "(:id, 'usuario.creado', 'usuario:1', 1, '{}'::jsonb, " + creado + ", " + creado + ", " + (publicado == null ? "NULL" : publicado) + ")")
                .param("id", id).update();
        return id;
    }
}
