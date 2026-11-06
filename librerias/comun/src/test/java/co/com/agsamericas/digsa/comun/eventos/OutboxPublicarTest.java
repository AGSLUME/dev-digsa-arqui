package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Pruebas 1 a 4 del diseño S2-OUTBOX: publicar al outbox (transacción obligatoria, rollback, tipo válido). */
class OutboxPublicarTest extends PruebaEventosBase {

    private static final Map<String, Object> DATOS = Map.of("documento", "99887766", "nombre", "Persona Ficticia Uno");

    @Test
    @DisplayName("1: publicar en una transacción deja una fila con el sobre correcto")
    void publicarDejaUnaFilaConElSobre() {
        Instant antes = Instant.now().minusSeconds(2);
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 3L, DATOS);
        Instant despues = Instant.now().plusSeconds(2);

        assertThat(id).isNotNull();
        assertThat(contar("outbox")).isEqualTo(1);
        var fila = jdbc.sql("SELECT id, tipo, agregado, version, cuerpo::text AS cuerpo, intentos, publicado, error, creado = siguiente AS igual "
                + "FROM outbox").query().singleRow();
        assertThat(fila.get("id")).isEqualTo(id);
        assertThat(fila.get("tipo")).isEqualTo("usuario.creado");
        assertThat(fila.get("agregado")).isEqualTo("usuario:7");
        assertThat(((Number) fila.get("version")).longValue()).isEqualTo(3L);
        assertThat(((Number) fila.get("intentos")).intValue()).isZero();
        assertThat(fila.get("publicado")).isNull();
        assertThat(fila.get("error")).isNull();
        assertThat(fila.get("igual")).as("siguiente = creado al publicar").isEqualTo(true);

        Sobre sobre = mapper.readValue((String) fila.get("cuerpo"), Sobre.class);
        assertThat(sobre.id()).isEqualTo(id);
        assertThat(sobre.tipo()).isEqualTo("usuario.creado");
        assertThat(sobre.version()).isEqualTo(3L);
        assertThat(sobre.origen()).isEqualTo("seguridad");
        assertThat(sobre.ocurrido()).isBetween(antes, despues);
        assertThat(sobre.datos()).isEqualTo(mapper.valueToTree(DATOS));
    }

    @Test
    @DisplayName("1: la traza (MDC traceId) viaja en el sobre; sin traza queda null")
    void trazaDelMdc() {
        org.slf4j.MDC.put("traceId", "traza-ficticia-123");
        try {
            publicarEnTransaccion("usuario.creado", "usuario:1", 1, DATOS);
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
        publicarEnTransaccion("usuario.creado", "usuario:2", 1, DATOS);
        var cuerpos = jdbc.sql("SELECT cuerpo::text FROM outbox ORDER BY agregado").query(String.class).list();
        assertThat(mapper.readValue(cuerpos.get(0), Sobre.class).traza()).isEqualTo("traza-ficticia-123");
        assertThat(mapper.readValue(cuerpos.get(1), Sobre.class).traza()).isNull();
    }

    @Test
    @DisplayName("1: el sobre serializado lleva los campos del ADR-006 con sus nombres")
    void camposDelSobre() {
        UUID id = publicarEnTransaccion("cuenta.bloqueada", "usuario:9", 5, DATOS);
        JsonNode cuerpo = mapper.readTree(jdbc.sql("SELECT cuerpo::text FROM outbox WHERE id = :id").param("id", id).query(String.class).single());
        assertThat(cuerpo.propertyNames()).contains("id", "tipo", "version", "ocurrido", "origen", "datos");
        assertThat(cuerpo.get("datos").get("documento").asString()).isEqualTo("99887766");
    }

    @Test
    @DisplayName("2: publicar sin transacción activa lanza IllegalStateException y no deja fila")
    void sinTransaccion() {
        assertThatThrownBy(() -> publicador.publicar("usuario.creado", "usuario:1", 1, DATOS)).isInstanceOf(IllegalStateException.class);
        assertThat(contar("outbox")).isZero();
    }

    @Test
    @DisplayName("3: si la transacción hace rollback, el evento desaparece")
    void rollback() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            publicador.publicar("usuario.creado", "usuario:1", 1, DATOS);
            throw new IllegalArgumentException("falla de negocio ficticia");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(contar("outbox")).isZero();

        tx.executeWithoutResult(s -> {
            publicador.publicar("usuario.creado", "usuario:2", 1, DATOS);
            s.setRollbackOnly();
        });
        assertThat(contar("outbox")).isZero();
    }

    @Test
    @DisplayName("3: varios eventos en la misma transacción: todos o ninguno")
    void todosONinguno() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            publicador.publicar("usuario.creado", "usuario:1", 1, DATOS);
            publicador.publicar("usuario.creado", "usuario:2", 1, DATOS);
            throw new IllegalStateException("falla ficticia");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(contar("outbox")).isZero();
        tx.executeWithoutResult(s -> {
            publicador.publicar("usuario.creado", "usuario:1", 1, DATOS);
            publicador.publicar("usuario.creado", "usuario:2", 1, DATOS);
        });
        assertThat(contar("outbox")).isEqualTo(2);
    }

    @ParameterizedTest(name = "4: tipo inválido «{0}»")
    @ValueSource(strings = {"Usuario Creado", "usuario", "Usuario.creado", "usuario.creado.", ".creado", "usuario..creado", "usuario.creado1",
            "usuario creado.x", "", "usuario.creado'; DROP TABLE outbox;--"})
    void tipoInvalido(String tipo) {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> publicador.publicar(tipo, "usuario:1", 1, DATOS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(contar("outbox")).isZero();
    }

    @Test
    @DisplayName("4: un tipo de más de 100 caracteres es inválido; uno de exactamente 100 es válido")
    void tipoLargo() {
        String limite = "a".repeat(49) + "." + "b".repeat(50); // 100 caracteres
        String largo = limite + "c";
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> publicador.publicar(largo, "usuario:1", 1, DATOS)))
                .isInstanceOf(IllegalArgumentException.class);
        publicarEnTransaccion(limite, "usuario:1", 1, DATOS);
        assertThat(contar("outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("4: tipos válidos con guion bajo y varios niveles")
    void tiposValidos() {
        publicarEnTransaccion("usuario.permisos_cambiados", "usuario:1", 1, DATOS);
        publicarEnTransaccion("visita.agenda.cerrada", "visita:1", 1, DATOS);
        assertThat(contar("outbox")).isEqualTo(2);
    }
}
