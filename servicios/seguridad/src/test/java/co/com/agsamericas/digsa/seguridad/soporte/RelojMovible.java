package co.com.agsamericas.digsa.seguridad.soporte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Reloj que la prueba adelanta a voluntad (como {@code soporte/RelojMovible} del monolito). Parte de la hora real al crearse. */
public class RelojMovible extends Clock {

    private final ZoneId zona;
    private volatile Instant ahora;

    public RelojMovible(ZoneId zona) {
        this.zona = zona;
        this.ahora = Instant.now();
    }

    public void avanzar(Duration d) {
        ahora = ahora.plus(d);
    }

    /** Vuelve a la hora real (cada prueba empieza en su propio «ahora»). */
    public void reiniciar() {
        ahora = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return this;
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
