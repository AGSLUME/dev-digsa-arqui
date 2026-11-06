package co.com.agsamericas.digsa.visitas.maestros;

/**
 * ¿Una obligación del contrato ya se calificó en alguna visita? La tabla {@code visitas_obligaciones} es de ejecución (VISITAS B), en el
 * mismo esquema; la implementación es {@link UsoObligacionesSql}. Es un puerto para poder simularlo en las pruebas.
 */
public interface UsoObligaciones {

    boolean calificada(long obligacionId);
}
