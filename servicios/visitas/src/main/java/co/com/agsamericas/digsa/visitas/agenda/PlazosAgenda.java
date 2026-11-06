package co.com.agsamericas.digsa.visitas.agenda;

import co.com.agsamericas.digsa.reglas.Plazos;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reglas de plazos (días hábiles, festivos, antelación de la notificación) con los parámetros y los festivos de ajuste de la base, como
 * {@code ServicioConsultaAgenda.plazos()} del monolito. A diferencia del monolito, que consultaba la base en cada llamada, guarda el
 * resultado 30 s: lo usan cada listado de la agenda y, en VISITAS B, el seguimiento, los recordatorios y el enlace del OPL.
 */
@Component
public class PlazosAgenda {

    static final Duration VIGENCIA = Duration.ofSeconds(30);

    /** Vale de {@code desde} a {@code hasta}: si el reloj retrocede (ajuste de hora, pruebas), se vuelve a leer. */
    private record EnCache(Plazos plazos, Instant desde, Instant hasta) {
        boolean vigente(Instant ahora) {
            return !ahora.isBefore(desde) && ahora.isBefore(hasta);
        }
    }

    private final JdbcClient jdbc;
    private final Clock reloj;
    private volatile EnCache cache;

    public PlazosAgenda(JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    public Plazos plazos() {
        Instant ahora = reloj.instant();
        EnCache c = cache;
        if (c != null && c.vigente(ahora)) {
            return c.plazos();
        }
        Plazos p = leer();
        cache = new EnCache(p, ahora, ahora.plus(VIGENCIA));
        return p;
    }

    /** Descarta lo guardado (p. ej. después de editar un parámetro de plazos). */
    public void olvidar() {
        cache = null;
    }

    private Plazos leer() {
        Map<String, Integer> reglas = new LinkedHashMap<>(Plazos.REGLAS);
        jdbc.sql("SELECT clave, valor FROM par_parametros WHERE clave = ANY(:c)").param("c", Plazos.REGLAS.keySet().toArray(String[]::new))
                .query(rs -> {
                    String v = rs.getString(2);
                    if (v != null && v.matches("^[0-9]{1,4}$")) {
                        reglas.put(rs.getString(1), Integer.parseInt(v));
                    }
                });
        Set<LocalDate> ajustes = Set.copyOf(jdbc.sql("SELECT fecha FROM par_festivos").query((rs, n) -> rs.getDate(1).toLocalDate()).list());
        return new Plazos(ajustes, reglas);
    }
}
