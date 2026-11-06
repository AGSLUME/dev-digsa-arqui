package co.com.agsamericas.digsa.visitas.maestros;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Revisión Java A2 y QA C1: {@link UsoObligacionesSql} sobre la tabla real. Mientras VISITAS B no la migre, ninguna obligación está calificada;
 * cuando exista, cuenta lo que haya. Si la tabla ya la creó la migración de B, la prueba solo inserta y borra su fila.
 */
class UsoObligacionesSqlTest extends PruebaIntegracion {

    @Autowired
    UsoObligaciones uso;
    @Autowired
    PostgreSQLContainer postgres;

    /** Conexión como dueño del esquema (Flyway), con la clave ficticia de las pruebas. */
    private Connection ddl() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "visitas_ddl", "visitas-ddl-solo-prueba");
    }

    @Test
    void sinLaTablaDeEjecucionNingunaEstaCalificadaYConFilaSi() throws SQLException {
        boolean existia = jdbc.sql("SELECT to_regclass('visitas_obligaciones') IS NOT NULL").query(Boolean.class).single();
        long id = 987_654_321L;
        if (!existia) {
            assertThat(uso.calificada(id)).isFalse();
        }
        try (Connection c = ddl(); Statement s = c.createStatement()) {
            if (!existia) {
                s.execute("CREATE TABLE visitas_obligaciones (obligacion_id bigint NOT NULL)");
                s.execute("GRANT SELECT ON visitas_obligaciones TO visitas");
                s.execute("INSERT INTO visitas_obligaciones (obligacion_id) VALUES (" + id + ")");
            }
            try {
                if (existia) {
                    // La tabla de B tiene más columnas obligatorias: solo se comprueba que una obligación sin filas no cuenta
                    assertThat(uso.calificada(id)).isFalse();
                } else {
                    assertThat(uso.calificada(id)).isTrue();
                    assertThat(uso.calificada(id + 1)).isFalse();
                    // Si existe pero no se puede leer, la excepción sube: el cambio de clave se rechaza (falla cerrado)
                    s.execute("REVOKE SELECT ON visitas_obligaciones FROM visitas");
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> uso.calificada(id))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
            } finally {
                if (!existia) {
                    s.execute("DROP TABLE visitas_obligaciones");
                }
            }
        }
    }
}
