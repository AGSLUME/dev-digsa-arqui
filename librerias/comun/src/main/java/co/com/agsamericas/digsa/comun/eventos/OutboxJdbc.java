package co.com.agsamericas.digsa.comun.eventos;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** {@link PublicadorEventos} sobre la tabla {@code outbox} del esquema del servicio (ver {@code lume/eventos/outbox.sql}). */
public class OutboxJdbc implements PublicadorEventos {

    static final Pattern TIPO = Pattern.compile("[a-z_]+(\\.[a-z_]+)+");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String origen;
    private final Clock reloj;

    public OutboxJdbc(JdbcClient jdbc, JsonMapper json, String origen, Clock reloj) {
        this.jdbc = jdbc;
        this.json = json;
        this.origen = origen;
        this.reloj = reloj;
    }

    @Override
    public UUID publicar(String tipo, String agregado, long version, Object datos) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El evento se publica dentro de la transacción del cambio (outbox, ADR-006).");
        }
        if (tipo == null || tipo.length() > 100 || !TIPO.matcher(tipo).matches()) {
            throw new IllegalArgumentException("Tipo de evento no válido: debe ser <agregado>.<hecho> en minúsculas.");
        }
        if (agregado == null || agregado.isBlank() || agregado.length() > 100) {
            throw new IllegalArgumentException("Agregado no válido.");
        }
        UUID id = UUID.randomUUID();
        Instant ahora = reloj.instant();
        Map<String, Object> sobre = new LinkedHashMap<>();
        sobre.put("id", id.toString());
        sobre.put("tipo", tipo);
        sobre.put("version", version);
        sobre.put("ocurrido", ahora.toString());
        sobre.put("origen", origen);
        sobre.put("traza", MDC.get("traceId"));
        sobre.put("datos", datos);
        jdbc.sql("""
                INSERT INTO outbox (id, tipo, agregado, version, cuerpo, creado, siguiente)
                VALUES (:id, :tipo, :agregado, :version, CAST(:cuerpo AS jsonb), :ahora, :ahora)""")
                .param("id", id).param("tipo", tipo).param("agregado", agregado).param("version", version)
                .param("cuerpo", json.writeValueAsString(sobre)).param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }
}
