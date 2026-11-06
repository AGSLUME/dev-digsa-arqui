package co.com.agsamericas.digsa.seguridad.soporte;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoria;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Doble del publicador de auditoría: guarda los eventos para que las pruebas revisen la bitácora. */
public class BitacoraCapturada implements PublicadorAuditoria {

    private final List<EventoAuditoria> eventos = new CopyOnWriteArrayList<>();

    @Override
    public void publicar(EventoAuditoria evento) {
        eventos.add(evento);
    }

    /** Los eventos de un tipo, en orden. */
    public List<EventoAuditoria> de(String tipo) {
        return eventos.stream().filter(e -> tipo.equals(e.tipo())).toList();
    }

    /** El último detalle de un tipo, o null. */
    public String ultimo(String tipo) {
        List<EventoAuditoria> l = de(tipo);
        return l.isEmpty() ? null : l.getLast().detalle();
    }
}
