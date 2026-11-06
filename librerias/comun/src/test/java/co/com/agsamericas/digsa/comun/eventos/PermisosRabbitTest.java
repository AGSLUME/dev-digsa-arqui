package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Permisos de RabbitMQ de {@code infra/rabbitmq/usuarios.sh} (ADR-006): el usuario {@code visitas} no escribe ni configura el exchange
 * de {@code seguridad}; sí configura lo suyo, y {@link Topologia#consumidor} no declara el exchange del productor.
 */
class PermisosRabbitTest extends PruebaEventosBase {

    private static final int ACCESS_REFUSED = 403;

    @Test
    @DisplayName("a: el usuario visitas NO puede publicar en seguridad.eventos (el canal se cierra con ACCESS_REFUSED, 403)")
    void visitasNoPublicaEnElExchangeDeSeguridad() throws Exception {
        int codigo = InfraEventos.codigoDeCierre("visitas",
                ch -> ch.basicPublish(EXCHANGE, "usuario.creado", new AMQP.BasicProperties(), "{}".getBytes()));
        assertThat(codigo).isEqualTo(ACCESS_REFUSED);
    }

    @Test
    @DisplayName("a: con confirmaciones, la publicación de visitas en seguridad.eventos tampoco se confirma")
    void visitasNoRecibeConfirmacion() throws Exception {
        int codigo = InfraEventos.codigoDeCierre("visitas", ch -> {
            ch.confirmSelect();
            ch.basicPublish(EXCHANGE, "usuario.creado", new AMQP.BasicProperties(), "{}".getBytes());
            ch.waitForConfirmsOrDie(5000);
        });
        assertThat(codigo).isEqualTo(ACCESS_REFUSED);
    }

    @Test
    @DisplayName("a: el productor sí publica en su exchange (control: el mismo camino con el usuario seguridad no se rechaza)")
    void seguridadSiPublica() throws Exception {
        int codigo = InfraEventos.codigoDeCierre("seguridad", ch -> {
            ch.confirmSelect();
            ch.basicPublish(EXCHANGE, "usuario.creado", new AMQP.BasicProperties(), "{}".getBytes());
            ch.waitForConfirmsOrDie(5000);
        });
        assertThat(codigo).isEqualTo(-1);
    }

    @Test
    @DisplayName("b: el usuario visitas NO puede declarar ni borrar seguridad.eventos (ACCESS_REFUSED)")
    void visitasNoDeclaraNiBorraElExchangeDeSeguridad() throws Exception {
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> ch.exchangeDeclare(EXCHANGE, "topic", true))).as("declarar").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> ch.exchangeDelete(EXCHANGE))).as("borrar").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.existeExchange(EXCHANGE)).as("el exchange sigue ahí").isTrue();
    }

    @Test
    @DisplayName("matriz: visitas NO puede enlazar una cola suya a seguridad.eventos con «#» (ACCESS_REFUSED en el bind); con usuario.# y cuenta.# sí")
    void visitasNoEnlazaTodo() throws Exception {
        String cola = "visitas.prueba-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "#");
        })).as("#").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "sesion.cerrada");
        })).as("sesion.cerrada (solo el gateway)").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "usuario.#");
            ch.queueBind(cola, EXCHANGE, "cuenta.#");
        })).as("usuario.# y cuenta.#").isEqualTo(-1);
    }

    @Test
    @DisplayName("matriz (comportamiento): una cola de visitas enlazada con «#» a seguridad.eventos NO recibe sesion.cerrada, solo lo permitido")
    void visitasConHashSoloRecibeLoPermitido() {
        String cola = "visitas.prueba-hash-" + UUID.randomUUID().toString().substring(0, 8);
        adminVisitas.declareQueue(new org.springframework.amqp.core.Queue(cola, true, false, false));
        try {
            adminVisitas.declareBinding(new org.springframework.amqp.core.Binding(cola, org.springframework.amqp.core.Binding.DestinationType.QUEUE, EXCHANGE, "#", null));
        } catch (RuntimeException e) {
            // rechazado en el bind: también cumple
        }
        try {
            publicarEnTransaccion("sesion.cerrada", "sesion:1", 1, java.util.Map.of("uid", 7));
            publicarEnTransaccion("usuario.creado", "usuario:7", 1, java.util.Map.of("uid", 7));
            publicarEnTransaccion("auditoria.evento", "x:1", 1, java.util.Map.of("uid", 7));
            assertThat(despachador.despachar()).isEqualTo(3);
            java.util.List<String> tipos = new java.util.concurrent.CopyOnWriteArrayList<>();
            // Consumo real (basic.consume), no solo basic.get: así lo hace un servicio
            try (com.rabbitmq.client.Connection con = InfraEventos.conexionNativa("visitas")) {
                com.rabbitmq.client.Channel ch = con.createChannel();
                try {
                    ch.basicConsume(cola, true, (tag, d) -> tipos.add(d.getProperties().getType()), tag -> {
                    });
                } catch (java.io.IOException e) {
                    // rechazado al consumir: también cumple
                }
                esperar(() -> tipos.size() >= 3, 3000);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(tipos).doesNotContain("sesion.cerrada", "auditoria.evento");
        } finally {
            adminVisitas.deleteQueue(cola);
        }
    }

    @Test
    @DisplayName("matriz: gateway NO puede enlazar usuario.creado ni #; sí sesion.* y usuario.estado_cambiado")
    void gatewayNoEnlazaUsuarioCreado() throws Exception {
        String cola = "gateway.prueba-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(InfraEventos.codigoDeCierre("gateway", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "usuario.creado");
        })).as("usuario.creado").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("gateway", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "#");
        })).as("#").isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("gateway", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "sesion.cerrada");
            ch.queueBind(cola, EXCHANGE, "usuario.estado_cambiado");
            ch.queueBind(cola, EXCHANGE, "usuario.permisos_cambiados");
        })).as("lo permitido").isEqualTo(-1);
    }

    @Test
    @DisplayName("matriz: ni el propio seguridad enlaza «#» en su exchange (solo el administrador); visitas no publica en visitas.eventos con clave de otro")
    void seguridadNoEnlazaTodoEnSuExchange() throws Exception {
        String cola = "seguridad.prueba-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(InfraEventos.codigoDeCierre("seguridad", ch -> {
            ch.queueDeclare(cola, true, false, false, null);
            ch.queueBind(cola, EXCHANGE, "#");
        })).isEqualTo(ACCESS_REFUSED);
    }

    @Test
    @DisplayName("b: visitas tampoco puede declarar colas ajenas (seguridad.*) ni usar el exchange por defecto")
    void visitasNoTocaRecursosAjenos() throws Exception {
        assertThat(InfraEventos.codigoDeCierre("visitas", ch -> ch.queueDeclare("seguridad.robada-" + UUID.randomUUID(), true, false, false, null)))
                .isEqualTo(ACCESS_REFUSED);
        assertThat(InfraEventos.codigoDeCierre("visitas",
                ch -> ch.basicPublish("", "visitas.cualquiera", new AMQP.BasicProperties(), "{}".getBytes()))).as("exchange por defecto")
                .isEqualTo(ACCESS_REFUSED);
    }

    @Test
    @DisplayName("c: Topologia declarada con el usuario visitas funciona (colas visitas.* y su .dlx) y no declara el exchange del productor")
    void topologiaConElUsuarioVisitas() throws Exception {
        String ausente = "ausente-" + UUID.randomUUID().toString().substring(0, 8).chars().mapToObj(ch -> String.valueOf((char) ('a' + ch % 26))).collect(java.util.stream.Collectors.joining()) + ".eventos"; // solo letras y guion
        assertThat(InfraEventos.existeExchange(ausente)).isFalse();

        try (ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, ausente, Duration.ofMillis(200), "usuario.#")) {
            // La declaración no falló aunque el exchange del productor no existe
            for (String q : new String[]{c.cola, c.reintento(), c.dlq()}) {
                assertThat(adminVisitas.getQueueProperties(q)).as(q).isNotNull();
            }
            assertThat(InfraEventos.existeExchange(c.dlx())).as("su .dlx").isTrue();
            assertThat(InfraEventos.existeExchange(ausente)).as("el exchange del productor no lo declara el consumidor").isFalse();
        }
    }

    @Test
    @DisplayName("c: con el exchange del productor ya existente, la cola queda enlazada a él (un evento despachado llega)")
    void topologiaSeEnlazaAlExchangeExistente() throws Exception {
        try (ConsumidorVisitas c = new ConsumidorVisitas(adminVisitas, EXCHANGE, Duration.ofMillis(200), "usuario.#")) {
            publicarEnTransaccion("usuario.creado", "usuario:7", 1, java.util.Map.of("uid", 7));
            assertThat(despachador.despachar()).isEqualTo(1);
            var m = rabbitVisitas.receive(c.cola, 5000);
            assertThat(m).isNotNull();
            assertThat(m.getMessageProperties().getReceivedExchange()).isEqualTo(EXCHANGE);
            assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo("usuario.creado");
        }
    }
}
