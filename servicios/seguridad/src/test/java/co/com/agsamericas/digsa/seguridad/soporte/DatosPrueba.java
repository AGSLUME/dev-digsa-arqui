package co.com.agsamericas.digsa.seguridad.soporte;

import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Usuarios ficticios para las pruebas, siempre con el dominio de la sesión ({@code @seguridad.prueba.local}, ADR-010). Escribe directo
 * en la base (como los datos semilla del monolito en sus pruebas); la API de administración llega en S2.
 */
public class DatosPrueba {

    /** Contraseña que cumple la política de reglas/Claves. Solo de prueba. */
    public static final String CLAVE = "Clave-Prueba-2026!";
    public static final String DOMINIO = "seguridad.prueba.local";

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    /** BCrypt de costo bajo: solo acelera las pruebas; el servicio verifica igual cualquier costo. */
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(4);

    private final JdbcClient jdbc;
    private final Clock reloj;

    public DatosPrueba(JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    /** Usuario activo con la contraseña {@link #CLAVE} ya definida (vigente 30 días). */
    public Ficticio usuario(Perfil... perfiles) {
        return nuevo().perfiles(perfiles).crear();
    }

    public Nuevo nuevo() {
        return new Nuevo();
    }

    private static final java.util.concurrent.atomic.AtomicLong IDS_COPIA = new java.util.concurrent.atomic.AtomicLong(9_000_000);

    /** Un establecimiento ESM ficticio en la copia de visitas (D5: semilla solo en pruebas). @return su id */
    public long puntoEsm(String nombre, String fuerza, boolean activo) {
        long id = IDS_COPIA.incrementAndGet();
        jdbc.sql("INSERT INTO puntos_copia (id, nombre, sigla, fuerza, unidad, activo) VALUES (:id, :n, :s, :f, 'ESM', :a)")
                .param("id", id).param("n", nombre).param("s", "ESM" + id % 1000).param("f", fuerza).param("a", activo).update();
        return id;
    }

    /** Una regional ficticia en la copia de visitas. @return su id */
    public long regional(String nombre, boolean activo) {
        long id = IDS_COPIA.incrementAndGet();
        jdbc.sql("INSERT INTO regionales_copia (id, nombre, activo) VALUES (:id, :n, :a)").param("id", id).param("n", nombre)
                .param("a", activo).update();
        return id;
    }

    /** Abre una sesión de LUME para el usuario (como tras un ingreso completo). @return el sid */
    public java.util.UUID abrirSesion(long usuarioId) {
        java.util.UUID sid = java.util.UUID.randomUUID();
        Instant ahora = reloj.instant();
        jdbc.sql("UPDATE sesiones SET cerrada = :a, motivo = 'salida' WHERE usuario_id = :u AND cerrada IS NULL")
                .param("a", ahora.atOffset(ZoneOffset.UTC)).param("u", usuarioId).update();
        jdbc.sql("INSERT INTO sesiones (id, usuario_id, inicio, ultimo, ip) VALUES (:id, :u, :a, :a, '203.0.113.10')")
                .param("id", sid).param("u", usuarioId).param("a", ahora.atOffset(ZoneOffset.UTC)).update();
        return sid;
    }

    /** Usuario creado. */
    public record Ficticio(long id, String usuario, String correo, String nombre) {
    }

    /** Constructor de un usuario de prueba con su estado de contraseña. */
    public final class Nuevo {
        private String usuario;
        private String clave = CLAVE;
        private Perfil[] perfiles = {Perfil.COORDINADOR};
        private boolean activo = true;
        private boolean claveDefinida = true;
        private boolean claveInicial;
        private boolean debeCambiar;
        private Duration claveVenceEn = Duration.ofDays(30);

        private Nuevo() {
            int n = SECUENCIA.incrementAndGet();
            this.usuario = "ficticio" + n + "." + System.nanoTime() % 100000;
        }

        public Nuevo usuario(String u) {
            this.usuario = u;
            return this;
        }

        public Nuevo clave(String c) {
            this.clave = c;
            return this;
        }

        public Nuevo perfiles(Perfil... p) {
            this.perfiles = p.length == 0 ? new Perfil[]{Perfil.COORDINADOR} : p;
            return this;
        }

        public Nuevo inactivo() {
            this.activo = false;
            return this;
        }

        /** Nunca definió su contraseña (recién creado): el ingreso le pide definirla por enlace. */
        public Nuevo sinClaveDefinida() {
            this.claveDefinida = false;
            return this;
        }

        /** Contraseña inicial asignada por el administrador (vale 72 h): el ingreso pide cambiarla. */
        public Nuevo claveInicial() {
            this.claveInicial = true;
            this.debeCambiar = true;
            this.claveVenceEn = Duration.ofHours(72);
            return this;
        }

        /** El administrador exigió el cambio (restablecer). */
        public Nuevo debeCambiarClave() {
            this.debeCambiar = true;
            return this;
        }

        /** La vigencia de la contraseña ya pasó. */
        public Nuevo claveVencida() {
            this.claveVenceEn = Duration.ofDays(-1);
            return this;
        }

        public Ficticio crear() {
            Instant ahora = reloj.instant();
            String correo = usuario + "@" + DOMINIO;
            String nombre = "Persona Ficticia " + usuario;
            long id = jdbc.sql("""
                    INSERT INTO usuarios (nombre, usuario, correo, clave_hash, activo, clave_definida, clave_inicial, debe_cambiar_clave,
                                          clave_vence, clave_actualizada, creado, actualizado)
                    VALUES (:n, :u, :c, :h, :a, :d, :i, :x, :v, :ahora, :ahora, :ahora) RETURNING id""")
                    .param("n", nombre).param("u", usuario).param("c", correo).param("h", BCRYPT.encode(clave)).param("a", activo)
                    .param("d", claveDefinida).param("i", claveInicial).param("x", debeCambiar)
                    .param("v", ahora.plus(claveVenceEn).atOffset(ZoneOffset.UTC)).param("ahora", ahora.atOffset(ZoneOffset.UTC))
                    .query(Long.class).single();
            for (Perfil p : perfiles) {
                jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", id).param("p", p.id()).update();
            }
            return new Ficticio(id, usuario, correo, nombre);
        }
    }
}
