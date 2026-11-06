package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.reglas.Sesion;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Parámetros de seguridad que edita el Administrador (V30 del monolito). Hoy: los minutos de inactividad (Anexo 5, 3.2: TIME OUT de 10
 * minutos «parametrizable y ajustable»). Sin valor guardado vale {@code lume.sesion-minutos}. Se lee en cada consulta de estado de
 * sesión, así que se guarda en memoria {@link #SEGUNDOS_CACHE} segundos. La edición llega en S2.
 */
@Service
public class ParametrosSeguridad {

    static final String SESION_MINUTOS = "sesion_minutos";
    static final int SEGUNDOS_CACHE = 30;

    private static final Logger LOG = LoggerFactory.getLogger(ParametrosSeguridad.class);

    private record Leido(int minutos, long hastaMs) {
    }

    private final JdbcClient jdbc;
    private final PropiedadesSeguridad cfg;
    private final Clock reloj;
    private volatile Leido cache;

    public ParametrosSeguridad(JdbcClient jdbc, PropiedadesSeguridad cfg, Clock reloj) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    /** Minutos de inactividad vigentes (1 a 480). Si la base no responde, el último valor conocido o el de la configuración. */
    public int minutosSesion() {
        long ahora = reloj.millis();
        Leido c = cache;
        if (c != null && ahora < c.hastaMs() && ahora >= c.hastaMs() - SEGUNDOS_CACHE * 1000L) {
            return c.minutos();
        }
        int minutos;
        try {
            minutos = guardado().map(Sesion::minutos).orElseGet(cfg::sesionMinutos);
        } catch (RuntimeException e) {
            // Falla cerrado hacia lo conocido: nunca sin plazo de inactividad
            LOG.error("[seguridad] No se pudo leer {}; se usa {}", SESION_MINUTOS, c == null ? "la configuración" : "el último valor", e);
            minutos = c == null ? cfg.sesionMinutos() : c.minutos();
        }
        cache = new Leido(minutos, ahora + SEGUNDOS_CACHE * 1000L);
        return minutos;
    }

    /** Olvida la caché (tras un cambio del administrador). */
    public void olvidar() {
        cache = null;
    }

    private Optional<String> guardado() {
        return jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = :c").param("c", SESION_MINUTOS).query(String.class).optional();
    }
}
