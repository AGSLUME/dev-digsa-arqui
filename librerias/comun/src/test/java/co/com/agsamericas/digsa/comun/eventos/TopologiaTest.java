package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

/** Topología del consumidor (diseño S2-OUTBOX, «Consumo»): sin broker, solo las declaraciones. */
class TopologiaTest {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Test
    @DisplayName("Topologia.cola arma <servicio>.<nombre>")
    void nombreDeLaCola() {
        assertThat(Topologia.cola("visitas", "usuarios")).isEqualTo("visitas.usuarios");
    }

    @Test
    @DisplayName("Topologia.consumidor declara las tres colas quorum con prefijo, el .dlx directo y los enlaces; no declara el exchange del productor")
    void declaraLaTopologia() {
        Declarables d = Topologia.consumidor("visitas", "usuarios", "seguridad.eventos", ESPERA, "usuario.#", "cuenta.#");

        Map<String, Queue> colas = d.getDeclarablesByType(Queue.class).stream().collect(Collectors.toMap(Queue::getName, Function.identity()));
        assertThat(colas).containsOnlyKeys("visitas.usuarios", "visitas.usuarios.reintento", "visitas.usuarios.dlq");
        colas.values().forEach(q -> {
            assertThat(q.isDurable()).as(q.getName()).isTrue();
            assertThat(q.getArguments()).as(q.getName()).containsEntry("x-queue-type", "quorum");
        });

        Queue principal = colas.get("visitas.usuarios");
        assertThat(principal.getArguments()).containsEntry("x-dead-letter-exchange", "visitas.usuarios.dlx")
                .containsEntry("x-dead-letter-routing-key", "reintento");
        Queue reintento = colas.get("visitas.usuarios.reintento");
        assertThat(reintento.getArguments()).containsEntry("x-dead-letter-exchange", "visitas.usuarios.dlx")
                .containsEntry("x-dead-letter-routing-key", "principal");
        assertThat(((Number) reintento.getArguments().get("x-message-ttl")).longValue()).isEqualTo(30_000L);
        assertThat(colas.get("visitas.usuarios.dlq").getArguments()).doesNotContainKey("x-message-ttl");

        // Solo se declara el .dlx (directo y durable); el exchange del productor NO
        var exchanges = d.getDeclarablesByType(Exchange.class);
        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.getFirst()).isInstanceOf(DirectExchange.class);
        assertThat(exchanges.getFirst().getName()).isEqualTo("visitas.usuarios.dlx");
        assertThat(exchanges.getFirst().isDurable()).isTrue();
        assertThat(exchanges).extracting(Exchange::getName).doesNotContain("seguridad.eventos");

        var enlaces = d.getDeclarablesByType(Binding.class);
        // Internos: del dlx a cada cola con "reintento", "principal" y "dlq"
        var internos = enlaces.stream().filter(b -> b.getExchange().equals("visitas.usuarios.dlx")).collect(Collectors.toMap(
                Binding::getRoutingKey, Binding::getDestination));
        assertThat(internos).containsOnly(Map.entry("reintento", "visitas.usuarios.reintento"), Map.entry("principal", "visitas.usuarios"),
                Map.entry("dlq", "visitas.usuarios.dlq"));
        // Al productor: uno por clave, hacia la cola principal, tolerando que el exchange aún no exista
        var alProductor = enlaces.stream().filter(b -> b.getExchange().equals("seguridad.eventos")).toList();
        assertThat(alProductor).extracting(Binding::getRoutingKey).containsExactlyInAnyOrder("usuario.#", "cuenta.#");
        assertThat(alProductor).allSatisfy(b -> {
            assertThat(b.getDestination()).isEqualTo("visitas.usuarios");
            assertThat(b.isIgnoreDeclarationExceptions()).isTrue();
        });
        assertThat(enlaces).hasSize(3 + 2);
    }

    @ParameterizedTest(name = "servicio inválido «{0}»")
    @ValueSource(strings = {"", "V", "Visitas", "1visitas", "a", "visi tas", "visitas.x", "visitas/x", "ñandu", "visitas2", "visitas_x"})
    void servicioInvalido(String servicio) {
        assertThatThrownBy(() -> Topologia.consumidor(servicio, "usuarios", "seguridad.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "nombre inválido «{0}»")
    @ValueSource(strings = {"", "U", "Usuarios", "1usuarios", "a", "usu arios", "usuarios.x", "usuarios*"})
    void nombreInvalido(String nombre) {
        assertThatThrownBy(() -> Topologia.consumidor("visitas", nombre, "seguridad.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("servicio y nombre nulos o de más de 41 caracteres son inválidos; el de 41 es válido")
    void largoYNulos() {
        assertThatThrownBy(() -> Topologia.consumidor(null, "usuarios", "seguridad.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Topologia.consumidor("visitas", null, "seguridad.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Topologia.consumidor("a".repeat(42), "usuarios", "seguridad.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Topologia.consumidor("visitas", "a".repeat(41), "seguridad.eventos", ESPERA, "usuario.#")).isNotNull();
    }

    @ParameterizedTest(name = "exchange del productor inválido «{0}»")
    @ValueSource(strings = {"", "seguridad", "seguridad.eventos.x", "Seguridad.eventos", ".eventos", "seguridad.eventosx", "amq.topic", "seguridad.otro"})
    void exchangeInvalido(String exchange) {
        assertThatThrownBy(() -> Topologia.consumidor("visitas", "usuarios", exchange, ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un servicio no consume su propio exchange")
    void noConsumeSuPropioExchange() {
        assertThatThrownBy(() -> Topologia.consumidor("visitas", "usuarios", "visitas.eventos", ESPERA, "usuario.#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Topologia.consumidor("visitas", "usuarios", "reportes.eventos", ESPERA, "x.#")).isNotNull();
    }

    @Test
    @DisplayName("sin claves no hay consumidor")
    void sinClaves() {
        assertThatThrownBy(() -> Topologia.consumidor("visitas", "usuarios", "seguridad.eventos", ESPERA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
