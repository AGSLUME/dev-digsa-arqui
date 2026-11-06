package co.com.agsamericas.digsa.visitas.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.comun.eventos.Sobre;
import co.com.agsamericas.digsa.visitas.soporte.ContenedoresPrueba;
import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * De punta a punta por RabbitMQ: un evento usuario.* publicado en seguridad.eventos (aquí lo publica el administrador en nombre de
 * seguridad) llega a la cola visitas.usuarios y actualiza la copia; uno con versión vieja no la pisa.
 */
class ConsumidorUsuariosTest extends PruebaIntegracion {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    RabbitMQContainer contenedorRabbit;

    private CachingConnectionFactory fabricaAdmin;
    private RabbitTemplate admin;

    @BeforeEach
    void conectar() {
        fabricaAdmin = new CachingConnectionFactory(contenedorRabbit.getHost(), contenedorRabbit.getAmqpPort());
        fabricaAdmin.setUsername(ContenedoresPrueba.ADMIN_USUARIO);
        fabricaAdmin.setPassword(ContenedoresPrueba.ADMIN_CLAVE);
        admin = new RabbitTemplate(fabricaAdmin);
    }

    @AfterEach
    void cerrar() {
        fabricaAdmin.destroy();
    }

    private void publicar(Sobre s) {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        p.setMessageId(s.id().toString());
        p.setType(s.tipo());
        p.setAppId("seguridad");
        admin.send(ConsumidorUsuarios.EXCHANGE, s.tipo(), new Message(JSON.writeValueAsBytes(s), p));
    }

    private Long version(long uid) throws InterruptedException {
        return jdbc.sql("SELECT version FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Long.class).optional().orElse(null);
    }

    private Long esperarVersion(long uid, long esperada) throws InterruptedException {
        Long v = null;
        for (int i = 0; i < 100; i++) {
            v = version(uid);
            if (v != null && v >= esperada) {
                return v;
            }
            Thread.sleep(200);
        }
        return v;
    }

    @Test
    void unEventoDeSeguridadActualizaLaCopia() throws Exception {
        long uid = 7_500_000_000L + System.nanoTime() % 1_000_000L;
        publicar(CopiaUsuariosTest.sobre("usuario.creado", 2,
                CopiaUsuariosTest.foto(uid, "Auditor Por Evento", true, List.of("AUDITOR"), "11223344", "equipo", null, null, null)));
        assertThat(esperarVersion(uid, 2)).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT nombre FROM usuarios_copia WHERE uid = :u").param("u", uid).query(String.class).single())
                .isEqualTo("Auditor Por Evento");

        // Uno viejo que llega después no pisa la copia; uno nuevo sí
        publicar(CopiaUsuariosTest.sobre("usuario.actualizado", 1,
                CopiaUsuariosTest.foto(uid, "Viejo", false, List.of(), null, "ninguno", null, null, null)));
        publicar(CopiaUsuariosTest.sobre("usuario.estado_cambiado", 3,
                CopiaUsuariosTest.foto(uid, "Auditor Por Evento", false, List.of("AUDITOR"), "11223344", "equipo", null, null, null)));
        assertThat(esperarVersion(uid, 3)).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT activo FROM usuarios_copia WHERE uid = :u").param("u", uid).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT nombre FROM usuarios_copia WHERE uid = :u").param("u", uid).query(String.class).single())
                .isEqualTo("Auditor Por Evento");
    }
}
