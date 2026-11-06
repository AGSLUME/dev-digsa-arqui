package co.com.agsamericas.digsa.visitas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.visitas.soporte.PruebaIntegracion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** VA1.0: el servicio {@code visitas} arranca con su rol y su esquema (modelo de dos roles) y protege sus rutas como servidor de recursos. */
class ArranqueTest extends PruebaIntegracion {

    @Test
    void laAplicacionUsaSuRolSinDdlYElEsquemaEsVisitas() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("visitas");
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("visitas");
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE intrusa (id int)").update()).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'visitas' AND table_name = 'visitas'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void laSaludEsPublica() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void unaApiSinTokenResponde401() throws Exception {
        mvc.perform(get("/api/catalogos")).andExpect(status().isUnauthorized());
    }

    @Test
    void unaRutaInternaSinTokenResponde401() throws Exception {
        mvc.perform(get("/interno/visitas/1")).andExpect(status().isUnauthorized());
    }
}
