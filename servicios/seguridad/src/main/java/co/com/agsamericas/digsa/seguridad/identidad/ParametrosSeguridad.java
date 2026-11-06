package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Sesion;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parámetros de seguridad que edita el Administrador (V30 del monolito). Hoy: los minutos de inactividad (Anexo 5, 3.2: TIME OUT de 10
 * minutos «parametrizable y ajustable»). Sin valor guardado vale {@code lume.sesion-minutos}. Se lee en cada consulta de estado de
 * sesión, así que se guarda en memoria {@link #SEGUNDOS_CACHE} segundos.
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
    private final BitacoraSeguridad bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private volatile Leido cache;

    public ParametrosSeguridad(JdbcClient jdbc, PropiedadesSeguridad cfg, BitacoraSeguridad bitacora, TransactionTemplate tx, Clock reloj) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.bitacora = bitacora;
        this.tx = tx;
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

    /**
     * Cambia los minutos de inactividad (Usuarios › Seguridad) y lo deja en la bitácora con el antes y el después. La fila se crea si no
     * existe y se bloquea antes de leer el anterior: dos administradores a la vez no dejan un «antes» falso. La caché se olvida después
     * del commit, así que una lectura concurrente no vuelve a guardar el valor viejo; en las demás réplicas aplica a más tardar en
     * {@link #SEGUNDOS_CACHE}.
     */
    public void cambiarMinutosSesion(Integer minutos, Permisos.Quien quien) {
        if (minutos == null) {
            throw ErrorNegocio.validacion("Escriba los minutos de inactividad.", "minutosSesion");
        }
        if (minutos < 1 || minutos > Sesion.MINUTOS_MAXIMO) {
            throw ErrorNegocio.validacion("Los minutos de inactividad deben ser un número entero de 1 a " + Sesion.MINUTOS_MAXIMO + ".",
                    "minutosSesion");
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Integer antes = tx.execute(t -> {
            jdbc.sql("""
                    INSERT INTO seg_parametros (clave, valor, actualizado_por, actualizado) VALUES (:c, :v, :u, :ahora)
                    ON CONFLICT (clave) DO NOTHING""")
                    .param("c", SESION_MINUTOS).param("v", String.valueOf(cfg.sesionMinutos())).param("u", quien.usuario()).param("ahora", ahora)
                    .update();
            int previo = jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = :c FOR UPDATE").param("c", SESION_MINUTOS).query(String.class)
                    .optional().map(Sesion::minutos).orElseGet(cfg::sesionMinutos);
            jdbc.sql("UPDATE seg_parametros SET valor = :v, actualizado_por = :u, actualizado = :ahora WHERE clave = :c")
                    .param("c", SESION_MINUTOS).param("v", String.valueOf(minutos)).param("u", quien.usuario()).param("ahora", ahora).update();
            return previo;
        });
        olvidar();
        bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "parametro_editado", "Parámetro de seguridad", Map.of(SESION_MINUTOS, antes),
                Map.of(SESION_MINUTOS, minutos), Set.of(), quien.id(), quien.usuario());
    }

    private Optional<String> guardado() {
        return jdbc.sql("SELECT valor FROM seg_parametros WHERE clave = :c").param("c", SESION_MINUTOS).query(String.class).optional();
    }
}
