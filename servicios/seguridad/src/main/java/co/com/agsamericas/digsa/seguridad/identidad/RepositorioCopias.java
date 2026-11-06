package co.com.agsamericas.digsa.seguridad.identidad;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Copia local de los establecimientos (ESM) y regionales de «visitas» (V6; ADR-002, D5). Solo valida el alcance de los usuarios ESM y OPL
 * regional y da las opciones del formulario. Vacía hasta que lleguen los eventos de visitas: crear un usuario ESM u OPL regional falla
 * cerrado (422).
 */
@Repository
public class RepositorioCopias {

    private final JdbcClient jdbc;

    public RepositorioCopias(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean esmActivo(Long id) {
        return id != null && id > 0 && jdbc.sql("SELECT count(*) FROM puntos_copia WHERE id = :id AND activo AND unidad = 'ESM'")
                .param("id", id).query(Long.class).single() > 0;
    }

    public boolean regionalActiva(Long id) {
        return id != null && id > 0 && jdbc.sql("SELECT count(*) FROM regionales_copia WHERE id = :id AND activo")
                .param("id", id).query(Long.class).single() > 0;
    }

    /** Establecimientos ESM activos, por fuerza y nombre (como {@code opcionesAlcance} del monolito). */
    public List<Map<String, Object>> esmActivos() {
        return jdbc.sql("SELECT id, nombre, sigla, fuerza FROM puntos_copia WHERE activo AND unidad = 'ESM' ORDER BY fuerza, nombre")
                .query().listOfRows();
    }

    public List<Map<String, Object>> regionalesActivas() {
        return jdbc.sql("SELECT id, nombre FROM regionales_copia WHERE activo ORDER BY nombre").query().listOfRows();
    }
}
