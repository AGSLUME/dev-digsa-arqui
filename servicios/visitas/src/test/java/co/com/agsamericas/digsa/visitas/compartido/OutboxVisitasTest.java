package co.com.agsamericas.digsa.visitas.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.comun.eventos.DespachadorOutbox;
import co.com.agsamericas.digsa.comun.eventos.PublicadorEventos;
import co.com.agsamericas.digsa.visitas.soporte.ContenedoresPrueba;
import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * VA2.O: el outbox de comun activado en visitas (ADR-006). Lo publicado en la transacción llega a {@code visitas.eventos} después de
 * {@code despachar()} (en pruebas el ciclo automático está apagado); un rollback no deja evento; la aplicación no puede borrar un evento
 * sin publicar (trigger de la plantilla). La cola de lectura la declara el administrador: el usuario visitas no enlaza «#».
 */
class OutboxVisitasTest extends PruebaIntegracion {

    private static final String EXCHANGE = "visitas.eventos";

    @Autowired
    PublicadorEventos publicador;
    @Autowired
    DespachadorOutbox despachador;
    @Autowired
    PlatformTransactionManager tm;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @Autowired
    RabbitMQContainer contenedorRabbit;

    private CachingConnectionFactory fabricaAdmin;
    private RabbitTemplate lector;
    private RabbitAdmin adminLector;
    private String cola;

    @BeforeEach
    void colaTemporal() {
        fabricaAdmin = new CachingConnectionFactory(contenedorRabbit.getHost(), contenedorRabbit.getAmqpPort());
        fabricaAdmin.setUsername(ContenedoresPrueba.ADMIN_USUARIO);
        fabricaAdmin.setPassword(ContenedoresPrueba.ADMIN_CLAVE);
        lector = new RabbitTemplate(fabricaAdmin);
        adminLector = new RabbitAdmin(fabricaAdmin);
        rabbitAdmin.initialize(); // declara visitas.eventos con sus argumentos (alternate-exchange), como al conectar
        cola = "visitas.prueba." + UUID.randomUUID();
        Queue q = new Queue(cola, false, true, false);
        adminLector.declareQueue(q);
        adminLector.declareBinding(BindingBuilder.bind(q).to(new TopicExchange(EXCHANGE, true, false)).with("#"));
    }

    @AfterEach
    void borrarCola() {
        adminLector.deleteQueue(cola);
        fabricaAdmin.destroy();
    }

    private long pendientes() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE publicado IS NULL").query(Long.class).single();
    }

    @Test
    void loPublicadoEnLaTransaccionLlegaAVisitasEventos() {
        String marca = "marca-" + UUID.randomUUID();
        UUID id = new TransactionTemplate(tm).execute(s -> publicador.publicar("prueba.publicada", "prueba:1", 1, Map.of("marca", marca)));
        despachador.despachar();
        Message m = null;
        for (int i = 0; i < 50 && m == null; i++) {
            Message x = lector.receive(cola, 200);
            if (x != null && new String(x.getBody(), StandardCharsets.UTF_8).contains(marca)) {
                m = x;
            }
        }
        assertThat(m).as("el evento llegó a la cola enlazada a visitas.eventos").isNotNull();
        String cuerpo = new String(m.getBody(), StandardCharsets.UTF_8).replace(" ", ""); // jsonb lo devuelve con espacios
        assertThat(cuerpo).contains("\"origen\":\"visitas\"").contains("\"tipo\":\"prueba.publicada\"").contains(id.toString());
        assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo("prueba.publicada");
        assertThat(jdbc.sql("SELECT publicado IS NOT NULL FROM outbox WHERE id = :i").param("i", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void unRollbackNoDejaEvento() {
        long antes = jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            publicador.publicar("prueba.revertida", "prueba:2", 1, Map.of("x", 1));
            s.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void sinTransaccionNoSePublica() {
        assertThatThrownBy(() -> publicador.publicar("prueba.suelta", "prueba:3", 1, Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void laAplicacionNoBorraUnEventoSinPublicar() {
        UUID id = new TransactionTemplate(tm).execute(s -> publicador.publicar("prueba.pendiente", "prueba:4", 1, Map.of()));
        assertThat(pendientes()).isPositive();
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM outbox WHERE id = :i").param("i", id).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("no se borra un evento sin publicar");
        despachador.despachar(); // queda publicado: ya se puede purgar
        assertThat(jdbc.sql("DELETE FROM outbox WHERE id = :i").param("i", id).update()).isEqualTo(1);
    }
}
