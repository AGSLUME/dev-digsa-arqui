package co.com.agsamericas.digsa.visitas.soporte;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoria;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Doble del puerto {@link PublicadorAuditoria} para las pruebas: guarda los eventos que el servicio publica (la bitácora ya no es una
 * tabla local sino un evento hacia {@code seguridad}, ADR-006). {@link PruebaIntegracion} lo registra como {@code @Primary} y lo
 * limpia antes de cada prueba.
 */
public class AuditoriaCapturada implements PublicadorAuditoria {

    private final List<EventoAuditoria> capturados = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publicar(EventoAuditoria evento) {
        capturados.add(evento);
    }

    /** Copia de los eventos publicados desde la última limpieza, en orden de publicación. */
    public List<EventoAuditoria> eventos() {
        synchronized (capturados) {
            return List.copyOf(capturados);
        }
    }

    public void limpiar() {
        capturados.clear();
    }
}
