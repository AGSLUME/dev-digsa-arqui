package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prueba 9 del diseño S2-OUTBOX: {@link ConsumoEventos} (sobre válido, origen verdadero, idempotencia, reintento con espera, DLQ y
 * rollback). El productor es {@code seguridad} (la aplicación de prueba); el consumidor es el usuario {@code visitas}, con una conexión,
 * un {@code RabbitTemplate} y un {@code RabbitAdmin} propios, de modo que los permisos de {@code usuarios.sh} se cumplen de verdad.
 * Reintentos configurados: 2 ({@code lume.eventos.reintentos}).
 */
class ConsumoEventosTest extends PruebaEventosBase {

    @Autowired
    ConsumoEventos consumoSeguridad;
    @Autowired
    Clock reloj;

    /** El consumo de visitas: publica en la DLQ con el usuario visitas. */
    private ConsumoEventos consumo;
    private final List<ConsumidorVisitas> consumidores = new ArrayList<>();

    @BeforeEach
    void consumoDeVisitas() {
        consumo = new ConsumoEventos(jdbc, tx, rabbitVisitas, mapper, 2, reloj);
    }

    @AfterEach
    void cerrarConsumidores() {
        consumidores.forEach(ConsumidorVisitas::close);
        consumidores.clear();
        adminVisitas.deleteExchange("visitas.eventos");
    }

    // ---------- Ayudas ----------

    private Sobre nuevoSobre(UUID id, String tipo, String origen) {
        JsonNode datos = mapper.valueToTree(Map.of("uid", 7, "documento", "99887766"));
        return new Sobre(id, tipo, 1L, Instant.now(), origen, null, datos);
    }

    private Message mensaje(Sobre s, String exchangeRecibido, String colaConsumidora) {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        p.setMessageId(s.id().toString());
        p.setType(s.tipo());
        p.setReceivedExchange(exchangeRecibido);
        p.setConsumerQueue(colaConsumidora);
        return new Message(mapper.writeValueAsBytes(s), p);
    }

    private Message mensajeDeCrudo(byte[] cuerpo, String exchangeRecibido) {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        p.setReceivedExchange(exchangeRecibido);
        p.setConsumerQueue("visitas.directa");
        return new Message(cuerpo, p);
    }

    private static Message sinRecibir(byte[] cuerpo, UUID id, String tipo) {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        p.setMessageId(id.toString());
        p.setType(tipo);
        return new Message(cuerpo, p);
    }

    /** Envía un sobre al exchange de seguridad (lo publica el dueño del exchange, aunque el sobre sea falso). */
    private void enviar(Sobre s, String claveEnrutamiento) {
        rabbit.send(EXCHANGE, claveEnrutamiento, sinRecibir(mapper.writeValueAsBytes(s), s.id(), s.tipo()));
    }

    private ConsumidorVisitas consumidor(Duration espera, java.util.function.Consumer<Sobre> accion) {
        ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, EXCHANGE, espera);
        consumidores.add(c);
        c.escuchar(consumo, EXCHANGE, accion);
        return c;
    }

    // ---------- leer ----------

    @Test
    @DisplayName("9: leer devuelve el sobre cuando el origen coincide con el exchange recibido y el esperado")
    void leerValido() {
        Sobre s = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        Sobre leido = consumo.leer(mensaje(s, EXCHANGE, "visitas.q"), EXCHANGE);
        assertThat(leido.id()).isEqualTo(s.id());
        assertThat(leido.tipo()).isEqualTo("usuario.creado");
        assertThat(leido.version()).isEqualTo(1L);
        assertThat(leido.origen()).isEqualTo("seguridad");
        assertThat(leido.datos()).isEqualTo(s.datos());
    }

    @Test
    @DisplayName("9: leer rechaza un origen falsificado (origen visitas llegando por seguridad.eventos)")
    void leerOrigenFalsificado() {
        Sobre falso = nuevoSobre(UUID.randomUUID(), "usuario.creado", "visitas");
        assertThatThrownBy(() -> consumo.leer(mensaje(falso, EXCHANGE, "visitas.q"), EXCHANGE)).isInstanceOf(EventoInvalido.class);
    }

    @Test
    @DisplayName("9: leer rechaza un mensaje que llegó por otro exchange que el esperado")
    void leerExchangeDistinto() {
        Sobre s = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        assertThatThrownBy(() -> consumo.leer(mensaje(s, "otro.eventos", "visitas.q"), EXCHANGE)).isInstanceOf(EventoInvalido.class);
        assertThatThrownBy(() -> consumo.leer(mensaje(s, EXCHANGE, "visitas.q"), "otro.eventos")).isInstanceOf(EventoInvalido.class);
    }

    @Test
    @DisplayName("9: tras un reintento (llega por <cola>.dlx) cuenta x-first-death-exchange; por cualquier otro exchange el header no vale")
    void leerTrasReintentoYHeaderFalso() {
        Sobre s = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        String cola = "visitas.q";

        Message tras = mensaje(s, cola + ".dlx", cola);
        tras.getMessageProperties().setHeader("x-first-death-exchange", EXCHANGE);
        assertThat(consumo.leer(tras, cola, EXCHANGE).id()).as("reintento legítimo").isEqualTo(s.id());

        Message ajenoDlx = mensaje(s, "otra.dlx", cola);
        ajenoDlx.getMessageProperties().setHeader("x-first-death-exchange", EXCHANGE);
        assertThatThrownBy(() -> consumo.leer(ajenoDlx, cola, EXCHANGE)).isInstanceOf(EventoInvalido.class);

        Message propio = mensaje(s, "visitas.eventos", cola);
        propio.getMessageProperties().setHeader("x-first-death-exchange", EXCHANGE);
        assertThatThrownBy(() -> consumo.leer(propio, cola, EXCHANGE)).as("header falso por un exchange propio").isInstanceOf(EventoInvalido.class);

        Message sinHeader = mensaje(s, cola + ".dlx", cola);
        assertThatThrownBy(() -> consumo.leer(sinHeader, cola, EXCHANGE)).isInstanceOf(EventoInvalido.class);
    }

    @ParameterizedTest(name = "9: leer rechaza un sobre sin el campo obligatorio «{0}»")
    @ValueSource(strings = {"id", "tipo", "version", "ocurrido", "origen", "datos"})
    void leerSinCampoObligatorio(String campo) {
        ObjectNode nodo = (ObjectNode) mapper.valueToTree(nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad"));
        nodo.remove(campo);
        assertThatThrownBy(() -> consumo.leer(mensajeDeCrudo(mapper.writeValueAsBytes(nodo), EXCHANGE), EXCHANGE))
                .isInstanceOf(EventoInvalido.class);
    }

    @ParameterizedTest(name = "9: leer rechaza un cuerpo que no es un sobre JSON: «{0}»")
    @ValueSource(strings = {"", "no es json", "{", "[]", "\"texto\"", "null", "{\"id\":\"no-es-uuid\"}"})
    void leerJsonInvalido(String cuerpo) {
        assertThatThrownBy(() -> consumo.leer(mensajeDeCrudo(cuerpo.getBytes(StandardCharsets.UTF_8), EXCHANGE), EXCHANGE))
                .isInstanceOf(EventoInvalido.class);
    }

    // ---------- primeraVez ----------

    private boolean primera(UUID id) {
        Boolean r = tx.execute(s -> consumoSeguridad.primeraVez(id));
        return Boolean.TRUE.equals(r);
    }

    @Test
    @DisplayName("9: primeraVez exige transacción; true la primera vez, false la segunda; con rollback el id no queda")
    void primeraVez() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> consumoSeguridad.primeraVez(id)).isInstanceOf(IllegalStateException.class);

        assertThat(primera(id)).isTrue();
        assertThat(primera(id)).isFalse();
        assertThat(contar("mensajes_procesados")).isEqualTo(1);

        UUID otro = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            assertThat(consumoSeguridad.primeraVez(otro)).isTrue();
            s.setRollbackOnly();
        });
        assertThat(primera(otro)).as("el rollback no dejó el id").isTrue();
    }

    // ---------- procesar (con el usuario visitas) ----------

    @Test
    @DisplayName("9: un mensaje repetido ejecuta la acción una sola vez")
    void mensajeRepetido() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
        });
        Sobre a = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        Sobre b = nuevoSobre(UUID.randomUUID(), "usuario.actualizado", "seguridad");
        enviar(a, "usuario.creado");
        enviar(a, "usuario.creado");
        enviar(b, "usuario.actualizado");

        assertThat(esperar(() -> c.procesados.contains(b.id()), 15_000)).as("llegó el último").isTrue();
        assertThat(c.llamadas.get()).as("a una vez y b una vez").isEqualTo(2);
        assertThat(c.procesados).containsExactly(a.id(), b.id());
        assertThat(contar("mensajes_procesados")).isEqualTo(2);
        assertThat(rabbitVisitas.receive(c.dlq(), 300)).as("un duplicado no es un error: no va a la DLQ").isNull();
    }

    @Test
    @DisplayName("9: un origen falsificado (visitas por seguridad.eventos) va a la DLQ sin ejecutar la acción")
    void origenFalsificadoVaALaDlq() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
        });
        Sobre falso = nuevoSobre(UUID.randomUUID(), "usuario.creado", "visitas");
        enviar(falso, "usuario.creado");

        Message enDlq = rabbitVisitas.receive(c.dlq(), 15_000);
        assertThat(enDlq).as("el sobre inválido llegó a la DLQ").isNotNull();
        assertThat(new String(enDlq.getBody(), StandardCharsets.UTF_8)).contains(falso.id().toString());
        assertThat(enDlq.getMessageProperties().getHeaders().get("x-lume-motivo")).isEqualTo("EventoInvalido");
        assertThat(c.llamadas.get()).as("la acción no se ejecutó").isZero();
        assertThat(contar("mensajes_procesados")).as("tampoco se marcó como procesado").isZero();
        assertThat(rabbitVisitas.receive(c.reintento(), 500)).as("EventoInvalido no se reintenta").isNull();
    }

    @Test
    @DisplayName("9: un cuerpo que no es JSON va a la DLQ sin reintentos")
    void cuerpoInvalidoVaALaDlq() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
        });
        rabbit.send(EXCHANGE, "usuario.creado", sinRecibir("esto no es un sobre".getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "usuario.creado"));
        assertThat(rabbitVisitas.receive(c.dlq(), 15_000)).isNotNull();
        assertThat(c.llamadas.get()).isZero();
    }

    @Test
    @DisplayName("9: una acción que falla siempre termina en visitas.<x>.dlq tras los reintentos (espera 200 ms, reintentos=2), con x-lume-motivo")
    void accionQueFallaTerminaEnDlq() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
            throw new IllegalStateException("fallo ficticio de la acción");
        });
        // Por el camino real: outbox y despachador de seguridad
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, Map.of("uid", 7, "documento", "99887766"));
        assertThat(despachador.despachar()).isEqualTo(1);

        Message enDlq = rabbitVisitas.receive(c.dlq(), 20_000);
        assertThat(enDlq).as("tras agotar los reintentos el mensaje llega a la DLQ").isNotNull();
        assertThat(enDlq.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(String.valueOf(enDlq.getMessageProperties().getHeaders().get("x-lume-motivo"))).isNotBlank().isNotEqualTo("null");
        assertThat(c.llamadas.get()).as("la acción se intentó: 1 + reintentos").isBetween(2, 4);
        assertThat(c.procesados).isEmpty();
        assertThat(contar("mensajes_procesados")).as("un fallo no deja el id como procesado").isZero();
    }

    @Test
    @DisplayName("9: un mensaje publicado por visitas en visitas.eventos con el header falso x-first-death-exchange=seguridad.eventos da EventoInvalido y va a la DLQ")
    void headerFalsoPorExchangePropio() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
        });
        TopicExchange propio = new TopicExchange("visitas.eventos", true, false);
        adminVisitas.declareExchange(propio);
        // En su propio exchange visitas solo puede enlazar la clave vacía (matriz de lectura de usuarios.sh): «^$»
        adminVisitas.declareBinding(new Binding(c.cola, Binding.DestinationType.QUEUE, "visitas.eventos", "", null));

        Sobre s = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        Message m = sinRecibir(mapper.writeValueAsBytes(s), s.id(), s.tipo());
        m.getMessageProperties().setHeader("x-first-death-exchange", EXCHANGE);
        rabbitVisitas.send("visitas.eventos", "", m);

        Message enDlq = rabbitVisitas.receive(c.dlq(), 15_000);
        assertThat(enDlq).as("va a la DLQ").isNotNull();
        assertThat(enDlq.getMessageProperties().getHeaders().get("x-lume-motivo")).isEqualTo("EventoInvalido");
        assertThat(c.llamadas.get()).as("la acción no se ejecutó").isZero();
        assertThat(contar("mensajes_procesados")).isZero();
    }

    @Test
    @DisplayName("9: si la acción falla, la transacción hace rollback y el id NO queda en mensajes_procesados (ni lo que la acción escribió)")
    void rollbackSiLaAccionFalla() {
        Sobre s = nuevoSobre(UUID.randomUUID(), "usuario.creado", "seguridad");
        Message m = mensaje(s, EXCHANGE, "visitas.directa");
        UUID rastro = UUID.randomUUID();

        assertThatThrownBy(() -> consumo.procesar(m, EXCHANGE, sobre -> {
            // La acción escribe en la misma base y luego falla: debe deshacerse junto con el id
            jdbc.sql("INSERT INTO mensajes_procesados (id, procesado) VALUES (:id, now())").param("id", rastro).update();
            throw new IllegalStateException("fallo ficticio");
        })).isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(contar("mensajes_procesados")).isZero();

        // La reentrega del mismo mensaje sí ejecuta la acción (el id no quedó marcado)
        AtomicInteger veces = new AtomicInteger();
        consumo.procesar(m, EXCHANGE, sobre -> veces.incrementAndGet());
        assertThat(veces.get()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM mensajes_procesados WHERE id = :id").param("id", s.id()).query(Long.class).single()).isEqualTo(1);

        // Y una tercera vez es un duplicado
        consumo.procesar(m, EXCHANGE, sobre -> veces.incrementAndGet());
        assertThat(veces.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("9: un cuerpo de más de 256 KiB es EventoInvalido (y va a la DLQ); el broker ya lo rechaza antes por max_message_size")
    void cuerpoGrandeEsInvalido() {
        JsonNode datos = mapper.valueToTree(Map.of("uid", 7, "relleno", "x".repeat(300 * 1024)));
        Sobre grande = new Sobre(UUID.randomUUID(), "usuario.creado", 1L, Instant.now(), "seguridad", null, datos);
        assertThatThrownBy(() -> consumo.leer(mensaje(grande, EXCHANGE, "visitas.q"), EXCHANGE)).isInstanceOf(EventoInvalido.class);
        Sobre justo = new Sobre(UUID.randomUUID(), "usuario.creado", 1L, Instant.now(), "seguridad", null,
                mapper.valueToTree(Map.of("uid", 7, "relleno", "x".repeat(200 * 1024))));
        assertThat(consumo.leer(mensaje(justo, EXCHANGE, "visitas.q"), EXCHANGE).id()).isEqualTo(justo.id());
    }

    @Test
    @DisplayName("9: el broker (max_message_size=256 KiB de 10-lume.conf) corta el canal o la conexión de quien publica más de 256 KiB")
    void brokerRechazaMasDe256KiB() throws Exception {
        try (com.rabbitmq.client.Connection c = InfraEventos.conexionNativa("seguridad")) {
            com.rabbitmq.client.Channel ch = c.createChannel();
            try {
                ch.basicPublish(EXCHANGE, "usuario.creado", new com.rabbitmq.client.AMQP.BasicProperties(), new byte[300 * 1024]);
            } catch (java.io.IOException e) {
                // también vale que falle al escribir
            }
            assertThat(esperar(() -> !ch.isOpen() || !c.isOpen(), 10_000)).as("el broker cerró el canal o la conexión").isTrue();
        }
    }

    @Test
    @DisplayName("9: datos que no son un objeto, o un tipo inválido, van a la DLQ")
    void datosNoObjetoOTipoInvalidoVanALaDlq() {
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
        });
        Sobre arreglo = new Sobre(UUID.randomUUID(), "usuario.creado", 1L, Instant.now(), "seguridad", null, mapper.valueToTree(List.of(1, 2)));
        Sobre tipoMalo = new Sobre(UUID.randomUUID(), "Usuario Creado", 1L, Instant.now(), "seguridad", null, mapper.valueToTree(Map.of("uid", 7)));
        enviar(arreglo, "usuario.creado");
        enviar(tipoMalo, "usuario.creado");
        assertThat(rabbitVisitas.receive(c.dlq(), 15_000)).isNotNull();
        assertThat(rabbitVisitas.receive(c.dlq(), 15_000)).isNotNull();
        assertThat(c.llamadas.get()).isZero();
    }

    @Test
    @DisplayName("9: un fallo transitorio de la base (TransientDataAccessResourceException) con reintentos=1 nunca termina en la DLQ, ni tras 3 ciclos")
    void falloTransitorioNoVaALaDlq() {
        consumo = new ConsumoEventos(jdbc, tx, rabbitVisitas, mapper, 1, reloj);
        ConsumidorVisitas c = consumidor(Duration.ofMillis(200), s -> {
            throw new org.springframework.dao.TransientDataAccessResourceException("fallo transitorio ficticio");
        });
        publicarEnTransaccion("usuario.creado", "usuario:7", 1, Map.of("uid", 7));
        assertThat(despachador.despachar()).isEqualTo(1);

        // Con reintentos=1 un fallo normal habría ido a la DLQ en el segundo intento; el transitorio sigue reintentándose
        assertThat(esperar(() -> c.llamadas.get() >= 4, 20_000)).as("al menos 4 intentos (1 + 3 ciclos)").isTrue();
        assertThat(rabbitVisitas.receive(c.dlq(), 500)).as("no está en la DLQ").isNull();
        assertThat(contar("mensajes_procesados")).isZero();
    }
}
