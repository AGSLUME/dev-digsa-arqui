package co.com.agsamericas.digsa.visitas.maestros;

/**
 * ¿Una obligación del contrato ya se calificó en alguna visita? La tabla {@code visitas_obligaciones} es de ejecución (VISITAS B): ella
 * entrega la implementación real como un bean de este tipo. Mientras no exista, {@link ServicioMaestros} entiende que responde
 * {@code false} (ninguna visita ha calificado obligaciones todavía).
 */
public interface UsoObligaciones {

    boolean calificada(long obligacionId);
}
