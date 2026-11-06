package co.com.agsamericas.digsa.visitas.usuarios;

import co.com.agsamericas.digsa.comun.eventos.ConsumoEventos;
import co.com.agsamericas.digsa.comun.eventos.Topologia;
import java.time.Duration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumidor de {@code seguridad.eventos} para la copia de usuarios (decisión D2): cola {@code visitas.usuarios} (quorum, con reintento
 * diferido y DLQ, por {@link Topologia}) con las claves {@code usuario.#} y {@code cuenta.#}. {@link ConsumoEventos} valida el sobre y el
 * origen, aplica cada evento una sola vez y reintenta; {@link CopiaUsuarios} aplica la foto con la regla de versión.
 */
@Configuration(proxyBeanMethods = false)
class ConsumidorUsuarios {

    static final String EXCHANGE = "seguridad.eventos";
    static final String COLA = "visitas.usuarios";

    private final ConsumoEventos consumo;
    private final CopiaUsuarios copia;

    ConsumidorUsuarios(ConsumoEventos consumo, CopiaUsuarios copia) {
        this.consumo = consumo;
        this.copia = copia;
    }

    @Bean
    Declarables colaUsuarios() {
        return Topologia.consumidor("visitas", "usuarios", EXCHANGE, Duration.ofSeconds(30), "usuario.#", "cuenta.#");
    }

    @RabbitListener(queues = COLA)
    void alRecibir(Message m) {
        consumo.procesar(m, EXCHANGE, copia::aplicar);
    }
}
