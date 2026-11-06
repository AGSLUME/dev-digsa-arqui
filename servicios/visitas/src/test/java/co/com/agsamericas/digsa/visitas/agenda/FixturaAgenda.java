package co.com.agsamericas.digsa.visitas.agenda;

import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Autowired;

/** Datos de prueba de agenda: puntos y visitas nuevos, con nombres únicos (la base se comparte entre pruebas). */
abstract class FixturaAgenda extends PruebaApi {

    @Autowired
    RepositorioVisitas repo;

    /** Punto ESM de dispensación nuevo en la fuerza y regional dadas. */
    protected long sembrarPunto(String fuerza, Long regionalId) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, regional_id, exige_qf)
                VALUES (:n, 'ESM', :f, 'Dispensación', :r, false) RETURNING id""")
                .param("n", unico("Punto prueba VA16 ")).param("f", fuerza).param("r", regionalId, java.sql.Types.BIGINT).query(Long.class).single();
    }

    protected long sembrarRegional() {
        return jdbc.sql("INSERT INTO par_regionales (nombre) VALUES (:n) RETURNING id").param("n", unico("Regional VA16 ")).query(Long.class).single();
    }

    /** Visita nueva por el repositorio (programada). Para «necesidad» pone fecha de solicitud. */
    protected long sembrarVisita(String tipo, long puntoId, long lider, LocalDate fecha) {
        LocalDate solicitud = "necesidad".equals(tipo) ? fecha.minusDays(3) : null;
        return repo.crear(tipo, puntoId, fecha, LocalTime.of(9, 0), lider, "Alcance de prueba VA1.6", solicitud, lider);
    }
}
