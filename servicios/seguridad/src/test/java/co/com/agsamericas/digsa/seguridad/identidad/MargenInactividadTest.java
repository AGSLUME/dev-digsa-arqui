package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Amarra {@link ServicioEstadoSesion#MARGEN_INFORME} al intervalo con que el gateway informa la actividad
 * ({@code FiltroSesionLume.INFORMAR_CADA_MS}). El gateway no depende de las librerías de LUME, así que se lee de su fuente (mismo
 * repositorio): si alguien cambia una constante sin la otra, o renombra la del gateway, esta prueba falla.
 */
class MargenInactividadTest {

    private static final Path FILTRO = Path.of("..", "gateway", "src", "main", "java", "co", "com", "agsamericas", "digsa", "gateway",
            "FiltroSesionLume.java");
    private static final Pattern CONSTANTE = Pattern.compile("\\bINFORMAR_CADA_MS\\s*=\\s*([0-9_]+)L?\\s*;");

    @Test
    @DisplayName("La segunda barrera de inactividad da al menos dos intervalos de informe del gateway (uno puede perderse)")
    void margenEsAlMenosDosIntervalos() throws Exception {
        assertThat(FILTRO).as("la fuente del filtro del gateway").exists();
        Matcher m = CONSTANTE.matcher(Files.readString(FILTRO, StandardCharsets.UTF_8));
        assertThat(m.find()).as("FiltroSesionLume declara INFORMAR_CADA_MS con un literal").isTrue();
        Duration intervalo = Duration.ofMillis(Long.parseLong(m.group(1).replace("_", "")));
        assertThat(intervalo).as("intervalo de informe del gateway").isPositive();
        assertThat(ServicioEstadoSesion.MARGEN_INFORME).as("MARGEN_INFORME ≥ 2 × INFORMAR_CADA_MS").isGreaterThanOrEqualTo(intervalo.multipliedBy(2));
    }
}
