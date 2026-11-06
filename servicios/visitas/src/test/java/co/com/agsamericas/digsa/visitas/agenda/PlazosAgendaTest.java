package co.com.agsamericas.digsa.visitas.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Plazos;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VA1.6: {@link PlazosAgenda#plazos()} lee par_parametros y par_festivos, con caché de 30 s.
 * SUPUESTO 1: la caché mide el tiempo con el bean {@code java.time.Clock} del servicio, que en las pruebas es el {@code RelojMovible};
 * si la implementación usa otro reloj, estas pruebas de caché deben cambiar.
 * SUPUESTO 2: el reloj se reinicia entre pruebas; una caché estampada en el futuro del reloj debe tenerse por vencida.
 * Cada prueba restaura lo que cambia y fuerza una relectura (avanza el reloj 31 s) al empezar y al terminar.
 */
class PlazosAgendaTest extends FixturaAgenda {

    @Autowired
    PlazosAgenda plazosAgenda;
    @Autowired
    org.testcontainers.postgresql.PostgreSQLContainer postgres;

    /** V10: el rol de la aplicación ya no escribe par_festivos; el festivo de ajuste lo carga el rol de migraciones (como una migración). */
    private org.springframework.jdbc.core.simple.JdbcClient ddl() {
        return org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), "visitas_ddl", "visitas-ddl-solo-prueba"));
    }

    private void vencerCache() {
        reloj.avanzar(Duration.ofSeconds(31));
    }

    private String parametro(String clave) {
        return jdbc.sql("SELECT valor FROM par_parametros WHERE clave = :c").param("c", clave).query(String.class).single();
    }

    private void fijar(String clave, String valor) {
        jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = :c").param("v", valor).param("c", clave).update();
    }

    /** Primer día hábil (según las reglas base) lejano, para usarlo de festivo de ajuste. */
    private static LocalDate diaHabilLejano() {
        LocalDate d = LocalDate.of(2043, 3, 3);
        while (!new Plazos().esHabil(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Test
    @DisplayName("lee los parámetros de la base")
    void leeLaBase() {
        vencerCache();
        Plazos p = plazosAgenda.plazos();
        for (String clave : Plazos.REGLAS.keySet()) {
            assertThat(p.regla(clave)).as(clave).isEqualTo(Integer.parseInt(parametro(clave)));
        }
    }

    @Test
    @DisplayName("un parámetro y un festivo cambiados se ven a los 31 s y no antes (caché de 30 s)")
    void cacheDeTreintaSegundos() {
        LocalDate festivo = diaHabilLejano();
        vencerCache();
        int original = plazosAgenda.plazos().regla("notif_dias_opl");
        String valorOriginal = parametro("notif_dias_opl");
        int nuevo = original + 7;
        try {
            fijar("notif_dias_opl", Integer.toString(nuevo));
            ddl().sql("INSERT INTO visitas.par_festivos (fecha, motivo) VALUES (:f, 'Festivo de prueba VA1.6')").param("f", festivo).update();

            reloj.avanzar(Duration.ofSeconds(29));
            Plazos antes = plazosAgenda.plazos();
            assertThat(antes.regla("notif_dias_opl")).as("dentro de los 30 s sigue el valor en caché").isEqualTo(original);
            assertThat(antes.esHabil(festivo)).as("el festivo aún no se ve").isTrue();

            reloj.avanzar(Duration.ofSeconds(2)); // 31 s desde la lectura
            Plazos despues = plazosAgenda.plazos();
            assertThat(despues.regla("notif_dias_opl")).isEqualTo(nuevo);
            assertThat(despues.esHabil(festivo)).as("el festivo de ajuste ya es no hábil").isFalse();
        } finally {
            fijar("notif_dias_opl", valorOriginal);
            ddl().sql("DELETE FROM visitas.par_festivos WHERE fecha = :f").param("f", festivo).update();
            vencerCache();
            plazosAgenda.plazos();
        }
    }

    @Test
    @DisplayName("dentro de la caché devuelve la misma instancia; vencida, relee")
    void mismaInstanciaEnCache() {
        vencerCache();
        Plazos a = plazosAgenda.plazos();
        reloj.avanzar(Duration.ofSeconds(10));
        assertThat(plazosAgenda.plazos()).isSameAs(a);
        reloj.avanzar(Duration.ofSeconds(25));
        assertThat(plazosAgenda.plazos()).isNotSameAs(a);
    }

    @Test
    @DisplayName("un valor no numérico (o de más de 4 dígitos) se ignora y queda la regla por defecto")
    void valorInvalidoUsaElPorDefecto() {
        String original = parametro("aviso_umbral_modulo");
        try {
            fijar("aviso_umbral_modulo", "abc");
            vencerCache();
            assertThat(plazosAgenda.plazos().regla("aviso_umbral_modulo")).isEqualTo(Plazos.REGLAS.get("aviso_umbral_modulo"));
            fijar("aviso_umbral_modulo", "12345");
            vencerCache();
            assertThat(plazosAgenda.plazos().regla("aviso_umbral_modulo")).isEqualTo(Plazos.REGLAS.get("aviso_umbral_modulo"));
            fijar("aviso_umbral_modulo", "7");
            vencerCache();
            assertThat(plazosAgenda.plazos().regla("aviso_umbral_modulo")).isEqualTo(7);
        } finally {
            fijar("aviso_umbral_modulo", original);
            vencerCache();
            plazosAgenda.plazos();
        }
    }
    @Test
    @DisplayName("editar un parámetro de plazos en Catálogos se ve de inmediato en la agenda (revisión Java A1)")
    void editarEnCatalogosDescartaLaCache() throws Exception {
        String original = parametro("notif_habiles_modulo");
        try {
            vencerCache();
            plazosAgenda.plazos(); // queda en caché con el valor actual
            String nuevo = "3".equals(original) ? "4" : "3";
            put("/api/catalogos/parametros/notif_habiles_modulo", token(nuevoUid(), java.util.List.of("Coordinador"), null, "CATALOGOS"),
                    java.util.Map.of("valor", nuevo))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(plazosAgenda.plazos().regla("notif_habiles_modulo")).isEqualTo(Integer.parseInt(nuevo));
        } finally {
            fijar("notif_habiles_modulo", original);
            plazosAgenda.olvidar();
        }
    }
}