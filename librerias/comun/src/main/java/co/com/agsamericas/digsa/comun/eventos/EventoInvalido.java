package co.com.agsamericas.digsa.comun.eventos;

/** Un mensaje que no es un sobre válido o que llega por un exchange que no corresponde a su origen. Va directo a la DLQ, sin reintentos. */
public class EventoInvalido extends RuntimeException {

    public EventoInvalido(String motivo) {
        super(motivo);
    }
}
