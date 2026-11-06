package co.com.agsamericas.digsa.visitas.maestros;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link UsoObligaciones} sobre {@code visitas_obligaciones} (ejecución, VISITAS B), en el mismo esquema: como el monolito, que hacía el
 * {@code EXISTS} directo. Mientras la tabla no exista (B aún no la migra), ninguna obligación se ha calificado. Si existe pero no se puede
 * leer, la excepción sube: el cambio de clave se rechaza (falla cerrado, revisión Java A2).
 */
@Component
class UsoObligacionesSql implements UsoObligaciones {

    private final JdbcClient jdbc;

    UsoObligacionesSql(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean calificada(long obligacionId) {
        boolean hayTabla = jdbc.sql("SELECT to_regclass('visitas_obligaciones') IS NOT NULL").query(Boolean.class).single();
        return hayTabla && jdbc.sql("SELECT EXISTS (SELECT 1 FROM visitas_obligaciones WHERE obligacion_id = :id)").param("id", obligacionId)
                .query(Boolean.class).single();
    }
}
