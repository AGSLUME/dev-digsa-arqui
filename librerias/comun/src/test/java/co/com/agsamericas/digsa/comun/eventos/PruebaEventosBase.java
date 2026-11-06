package co.com.agsamericas.digsa.comun.eventos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base de las pruebas de eventos de {@code comun}: PostgreSQL y RabbitMQ reales (Testcontainers), origen {@code seguridad} (usuario de RabbitMQ {@code seguridad}, exchange
 * {@code seguridad.eventos}) y ciclo automático apagado ({@code cada-ms=0}): las pruebas llaman a {@code despachar()} a mano. El broker
 * se alcanza por un proxy TCP para poder «caerlo» y «reponerlo».
 */
@SpringBootTest(classes = AplicacionPrueba.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "lume.eventos.origen=seguridad",
        "lume.eventos.cada-ms=0",
        "lume.eventos.lote=25",
        "lume.eventos.reintentos=2",
        "spring.rabbitmq.publisher-confirm-type=simple"})
abstract class PruebaEventosBase {

    static final String EXCHANGE = "seguridad.eventos";

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", InfraEventos.POSTGRES_CONTENEDOR::getJdbcUrl);
        r.add("spring.datasource.username", InfraEventos.POSTGRES_CONTENEDOR::getUsername);
        r.add("spring.datasource.password", InfraEventos.POSTGRES_CONTENEDOR::getPassword);
        r.add("spring.rabbitmq.host", () -> "127.0.0.1");
        r.add("spring.rabbitmq.port", () -> InfraEventos.PROXY.puerto());
        r.add("spring.rabbitmq.username", () -> "seguridad");
        r.add("spring.rabbitmq.password", () -> InfraEventos.clave("seguridad"));
    }

    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected RabbitAdmin admin;
    @Autowired
    protected JsonMapper mapper;
    @Autowired
    protected PublicadorEventos publicador;
    @Autowired
    protected DespachadorOutbox despachador;

    /** El consumidor simulado: el usuario {@code visitas} (solo configura y escribe {@code visitas.*}; lee sus colas y los {@code *.eventos}). */
    protected final RabbitTemplate rabbitVisitas = new RabbitTemplate(InfraEventos.VISITAS);
    protected final RabbitAdmin adminVisitas = new RabbitAdmin(InfraEventos.VISITAS);

    /** Plomería con el administrador: las colas de lectura de las pruebas enlazan «#», cosa que ningún servicio puede en seguridad.eventos. */
    protected final RabbitTemplate rabbitLector = new RabbitTemplate(InfraEventos.ADMIN);
    protected final RabbitAdmin adminLector = new RabbitAdmin(InfraEventos.ADMIN);

    private final List<String> colasCreadas = new ArrayList<>();

    @BeforeEach
    void limpiarTablas() {
        InfraEventos.PROXY.reponer();
        admin.initialize(); // el dueño (seguridad) declara su exchange con sus argumentos (alternate-exchange), como al conectar
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM mensajes_procesados").update();
    }

    @AfterEach
    void reponerBrokerYBorrarColas() {
        InfraEventos.PROXY.reponer();
        for (String c : colasCreadas) {
            adminLector.deleteQueue(c);
        }
        colasCreadas.clear();
    }

    /** Publica un evento dentro de una transacción que se confirma. */
    protected UUID publicarEnTransaccion(String tipo, String agregado, long version, Object datos) {
        return tx.execute(s -> publicador.publicar(tipo, agregado, version, datos));
    }

    /** Una cola temporal (no exclusiva: sobrevive a la caída del proxy) enlazada al exchange con la clave dada. */
    protected String colaEnlazada(String clave) {
        String nombre = "seguridad.prueba." + UUID.randomUUID();
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue cola = new Queue(nombre, true, false, false); // durable y sin autoborrado (RabbitMQ 4; receive() cancela su consumidor); se borra al final
        adminLector.declareQueue(cola);
        adminLector.declareBinding(BindingBuilder.bind(cola).to(exchange).with(clave));
        colasCreadas.add(nombre);
        return nombre;
    }

    /** Lee todo lo que haya en la cola; termina cuando no llega nada en {@code esperaMs}. */
    protected List<Message> leerTodo(String cola, long esperaMs) {
        List<Message> r = new ArrayList<>();
        Message m;
        while ((m = rabbitLector.receive(cola, esperaMs)) != null) {
            r.add(m);
        }
        return r;
    }

    /** Espera (hasta {@code ms}) a que se cumpla la condición. */
    protected static boolean esperar(BooleanSupplier condicion, long ms) {
        long limite = System.nanoTime() + ms * 1_000_000;
        while (System.nanoTime() < limite) {
            if (condicion.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condicion.getAsBoolean();
    }

    protected long contar(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla).query(Long.class).single();
    }
}
