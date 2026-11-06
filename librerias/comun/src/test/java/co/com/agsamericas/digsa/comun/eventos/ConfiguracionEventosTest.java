package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Prueba 11 del diseño S2-OUTBOX: sin {@code lume.eventos.origen} la autoconfiguración no crea nada (el servicio {@code hola} no cambia). */
class ConfiguracionEventosTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfiguracionEventos.class));

    @Test
    @DisplayName("11: sin lume.eventos.origen no hay PublicadorEventos, despachador, consumo ni exchange")
    void sinOrigenNoHayNada() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(PublicadorEventos.class);
            assertThat(ctx).doesNotHaveBean(DespachadorOutbox.class);
            assertThat(ctx).doesNotHaveBean(ConsumoEventos.class);
            assertThat(ctx).doesNotHaveBean(TopicExchange.class);
        });
    }

    @Test
    @DisplayName("11: con otras propiedades lume.eventos.* pero sin origen tampoco se crea nada")
    void sinOrigenAunqueHayaOtrasPropiedades() {
        contexto.withPropertyValues("lume.eventos.cada-ms=0", "lume.eventos.lote=10").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(PublicadorEventos.class);
            assertThat(ctx).doesNotHaveBean(DespachadorOutbox.class);
        });
    }
}
