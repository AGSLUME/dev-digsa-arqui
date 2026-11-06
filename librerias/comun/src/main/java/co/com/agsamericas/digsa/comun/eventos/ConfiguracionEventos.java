package co.com.agsamericas.digsa.comun.eventos;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox y consumo de eventos (ADR-006) para el servicio que declara {@code lume.eventos.origen} y tiene JDBC y RabbitMQ. Sin esa
 * propiedad no se crea nada. El JSON del sobre usa su propio {@link JsonMapper}: su forma no depende de la configuración de Jackson de
 * cada servicio.
 */
@AutoConfiguration(afterName = {
        "co.com.agsamericas.digsa.comun.utilidades.Reloj",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"})
@ConditionalOnClass({RabbitTemplate.class, JdbcClient.class})
@ConditionalOnProperty(prefix = "lume.eventos", name = "origen")
@EnableConfigurationProperties(PropiedadesEventos.class)
public class ConfiguracionEventos {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    @ConditionalOnMissingBean
    PublicadorEventos publicadorEventos(JdbcClient jdbc, PropiedadesEventos props, Clock reloj) {
        return new OutboxJdbc(jdbc, JSON, props.origen(), reloj);
    }

    @Bean
    @ConditionalOnMissingBean
    DespachadorOutbox despachadorOutbox(JdbcClient jdbc, PlatformTransactionManager tm, RabbitTemplate rabbit, PropiedadesEventos props,
                                        Clock reloj) {
        return new DespachadorOutbox(jdbc, new TransactionTemplate(tm), rabbit, props, reloj);
    }

    @Bean
    @ConditionalOnMissingBean
    ConsumoEventos consumoEventos(JdbcClient jdbc, PlatformTransactionManager tm, RabbitTemplate rabbit, PropiedadesEventos props, Clock reloj) {
        return new ConsumoEventos(jdbc, new TransactionTemplate(tm), rabbit, JSON, props.reintentos(), reloj);
    }

    /** Pendientes y antigüedad del outbox para Prometheus, si el servicio tiene Micrometer. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class Metricas {

        @Bean
        @ConditionalOnMissingBean
        MetricasOutbox metricasOutbox(JdbcClient jdbc, Clock reloj) {
            return new MetricasOutbox(jdbc, reloj);
        }
    }

    /** Reintenta los enlaces de las colas de consumo a exchanges ajenos que aún no existen (ver {@link Topologia}). */
    @Bean
    @ConditionalOnMissingBean
    EnlazadorEventos enlazadorEventos(RabbitTemplate rabbit, ObjectProvider<Declarables> declarables, ObjectProvider<Binding> enlaces,
                                      PropiedadesEventos props) {
        List<Binding> todos = new ArrayList<>(enlaces.orderedStream().toList());
        declarables.orderedStream().forEach(d -> todos.addAll(d.getDeclarablesByType(Binding.class)));
        return new EnlazadorEventos(rabbit, todos, props.reenlazarMs());
    }

    /**
     * El exchange propio, durable (lo declara RabbitAdmin al conectar; solo el dueño lo declara), con un exchange alterno
     * {@code <origen>.sin-ruta}: un evento que no encuentra ninguna cola (consumidor que aún no enlazó la suya) no se pierde en silencio,
     * queda en la cola {@code <origen>.sin-ruta} para revisión y reproceso. Tope de 100 000 mensajes (se descartan los más viejos); las
     * copias se reconcilian además por la exportación interna.
     */
    @Bean
    Declarables exchangeEventos(PropiedadesEventos props) {
        String sinRuta = props.origen() + ".sin-ruta";
        TopicExchange propio = ExchangeBuilder.topicExchange(props.exchange()).durable(true).alternate(sinRuta).build();
        FanoutExchange alterno = ExchangeBuilder.fanoutExchange(sinRuta).durable(true).build();
        Queue cola = QueueBuilder.durable(sinRuta).quorum().maxLength(SIN_RUTA_MAXIMO).overflow(QueueBuilder.Overflow.dropHead).build();
        return new Declarables(propio, alterno, cola, BindingBuilder.bind(cola).to(alterno));
    }

    private static final int SIN_RUTA_MAXIMO = 100_000;
}
