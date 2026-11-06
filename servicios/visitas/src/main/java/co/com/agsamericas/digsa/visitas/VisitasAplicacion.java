package co.com.agsamericas.digsa.visitas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servicio {@code visitas} (ADR-002): un solo desplegable que construyen dos sesiones. Paquetes por área para no pisarse
 * ({@code ACUERDOS_OLEADA_2.md}): visitas-a es dueña de {@code catalogos}, {@code maestros}, {@code agenda}, {@code usuarios} y de lo
 * compartido (configuración, esquema); visitas-b, de {@code ejecucion}, {@code hallazgos}, {@code opl}, {@code tablero} y
 * {@code exportacion}.
 */
@SpringBootApplication
public class VisitasAplicacion {

    public static void main(String[] args) {
        SpringApplication.run(VisitasAplicacion.class, args);
    }
}
