package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.TopicExchange;

/** {@link EnlazadorEventos}: el consumidor arranca antes que el productor y el enlace se completa cuando el productor declara su exchange. */
class EnlazadorEventosTest extends PruebaEventosBase {

    @Test
    @DisplayName("d: con el productor aún sin exchange, enlazar() deja 1 pendiente sin tumbar la conexión; al declararlo el productor, queda 0 y un evento enrutado llega a la cola de visitas")
    void consumidorArrancaAntesQueElProductor() throws Exception {
        // OJO: no se borra seguridad.eventos para simularlo: al borrar un exchange RabbitMQ pierde sus permisos por tema (matriz de ADR-006).
        // Se usa otro exchange *.eventos que aún no existe; lo declara después el administrador (en producción, su dueño).
        String tardio = "tardio-" + java.util.UUID.randomUUID().toString().substring(0, 8).chars()
                .mapToObj(ch -> String.valueOf((char) ('a' + ch % 26))).collect(java.util.stream.Collectors.joining()) + ".eventos";
        assertThat(InfraEventos.existeExchange(tardio)).isFalse();
        try (ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, tardio, Duration.ofMillis(200), "usuario.#")) {
            List<Binding> enlaces = c.topologia.getDeclarablesByType(Binding.class);
            EnlazadorEventos enlazador = new EnlazadorEventos(rabbitVisitas, enlaces, 60_000);

            assertThat(enlazador.enlazar()).as("el exchange no existe: sigue 1 pendiente").isEqualTo(1);
            assertThat(enlazador.enlazar()).as("y sigue sin existir (la conexión sigue sirviendo)").isEqualTo(1);
            assertThat(adminVisitas.getQueueProperties(c.cola)).as("la cola quedó declarada").isNotNull();

            // Llega el productor y declara su exchange
            adminLector.declareExchange(new TopicExchange(tardio, true, false));
            assertThat(enlazador.enlazar()).as("ya existe: no queda nada pendiente").isZero();

            Sobre s = new Sobre(java.util.UUID.randomUUID(), "usuario.creado", 1L, java.time.Instant.now(), tardio.replace(".eventos", ""), null,
                    mapper.valueToTree(Map.of("uid", 7)));
            org.springframework.amqp.core.MessageProperties props = new org.springframework.amqp.core.MessageProperties();
            props.setType("usuario.creado");
            rabbitLector.send(tardio, "usuario.creado", new Message(mapper.writeValueAsBytes(s), props));
            Message m = rabbitVisitas.receive(c.cola, 5000);
            assertThat(m).as("el evento llegó a la cola de visitas").isNotNull();
            assertThat(m.getMessageProperties().getType()).isEqualTo("usuario.creado");
        } finally {
            adminLector.deleteExchange(tardio);
        }
    }

    @Test
    @DisplayName("d: los enlaces internos (al .dlx) no cuentan como pendientes: sin enlaces ajenos enlazar() devuelve 0")
    void sinEnlacesAjenosNoHayPendientes() {
        try (ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, EXCHANGE, Duration.ofMillis(200), "usuario.#")) {
            List<Binding> soloInternos = c.topologia.getDeclarablesByType(Binding.class).stream()
                    .filter(b -> b.getExchange().equals(c.dlx())).toList();
            assertThat(new EnlazadorEventos(rabbitVisitas, soloInternos, 60_000).enlazar()).isZero();
        }
    }

    @Test
    @DisplayName("d: con el exchange ya existente, enlazar() es idempotente y devuelve 0")
    void enlazarConExchangeExistente() {
        try (ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, EXCHANGE, Duration.ofMillis(200), "usuario.#", "cuenta.#")) {
            EnlazadorEventos enlazador = new EnlazadorEventos(rabbitVisitas, c.topologia.getDeclarablesByType(Binding.class), 60_000);
            assertThat(enlazador.enlazar()).isZero();
            assertThat(enlazador.enlazar()).isZero();
        }
    }
}
