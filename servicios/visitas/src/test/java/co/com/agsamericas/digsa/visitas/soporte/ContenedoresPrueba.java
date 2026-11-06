package co.com.agsamericas.digsa.visitas.soporte;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Contenedores de las pruebas de integración de visitas (los reutiliza visitas-b):
 * <ul>
 *   <li>PostgreSQL: la misma imagen y digest que {@code infra/docker-compose.yml} (ADR-005, ADR-012) y el mismo script de arranque
 *       {@code infra/postgres/init/01-esquemas.sh}. Modelo de dos roles de D8: Flyway con {@code visitas_ddl} y la aplicación con
 *       {@code visitas}, que solo tiene los GRANT de cada migración (un GRANT faltante hace fallar la prueba).</li>
 *   <li>RabbitMQ: la misma imagen, el mismo {@code command} y los mismos tres archivos del compose ({@code usuarios.sh},
 *       {@code 10-lume.conf} y {@code definiciones.json}). La aplicación se conecta como {@code visitas}, no como administrador.</li>
 * </ul>
 * Los contenedores son <b>uno por JVM</b>, compartidos por todos los contextos de prueba (p. ej. el del perfil «otro»): levantar otro
 * RabbitMQ por contexto tarda minutos con el equipo cargado. Los detiene Testcontainers (Ryuk) al terminar la JVM. Ojo: también son globales
 * la base y las colas; un contexto extra no debe despachar el outbox ni escuchar colas (ver InternoPruebasAusenteTest).
 * Las claves son ficticias, solo de prueba.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContenedoresPrueba {

    public static final DockerImageName POSTGRES = DockerImageName
            .parse("postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32")
            .asCompatibleSubstituteFor("postgres");

    public static final DockerImageName RABBITMQ = DockerImageName
            .parse("rabbitmq:4.3.7-management@sha256:6e51f8f18e055d6dc3d94316db0073da64d811c2e19744661fae06b03c1d6fa8")
            .asCompatibleSubstituteFor("rabbitmq");

    private static final String CLAVE_APP = "visitas-app-solo-prueba";
    private static final String CLAVE_DDL = "visitas-ddl-solo-prueba";

    /** Administrador de RabbitMQ de las pruebas (solo la plomería: colas de lectura que enlazan «#»). Clave ficticia. */
    public static final String ADMIN_USUARIO = "lume_admin";
    public static final String ADMIN_CLAVE = "admin-rabbit-solo-prueba";
    private static final String CLAVE_RABBIT = "visitas-rabbit-solo-prueba";

    private static PostgreSQLContainer postgresCompartido;
    private static RabbitMQContainer rabbitmqCompartido;

    @Bean(destroyMethod = "")
    PostgreSQLContainer postgres() {
        return postgresCompartido();
    }

    private static synchronized PostgreSQLContainer postgresCompartido() {
        if (postgresCompartido != null) {
            return postgresCompartido;
        }
        PostgreSQLContainer c = new PostgreSQLContainer(POSTGRES)
                .withDatabaseName("lume")
                .withUsername("lume_admin")
                .withPassword("admin-solo-prueba")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/postgres/init/01-esquemas.sh", 0755),
                        "/docker-entrypoint-initdb.d/01-esquemas.sh")
                .withStartupTimeout(java.time.Duration.ofMinutes(5));
        for (String svc : new String[]{"SEGURIDAD", "VISITAS", "REPORTES", "NOTIFICACIONES"}) {
            c.withEnv(svc + "_DB_CLAVE", svc.toLowerCase() + "-app-solo-prueba");
            c.withEnv(svc + "_DB_CLAVE_DDL", svc.toLowerCase() + "-ddl-solo-prueba");
        }
        c.start();
        postgresCompartido = c;
        return c;
    }

    @Bean
    DynamicPropertyRegistrar propiedadesPostgres(PostgreSQLContainer postgres) {
        return r -> {
            r.add("spring.datasource.url", postgres::getJdbcUrl);
            r.add("spring.datasource.username", () -> "visitas");
            r.add("spring.datasource.password", () -> CLAVE_APP);
            r.add("spring.flyway.user", () -> "visitas_ddl");
            r.add("spring.flyway.password", () -> CLAVE_DDL);
        };
    }

    @Bean(destroyMethod = "")
    RabbitMQContainer rabbitmq() throws Exception {
        return rabbitmqCompartido();
    }

    private static synchronized RabbitMQContainer rabbitmqCompartido() throws Exception {
        if (rabbitmqCompartido != null) {
            return rabbitmqCompartido;
        }
        RabbitMQContainer c = new RabbitMQContainer(RABBITMQ)
                // Los mismos tres archivos que el compose: matriz de permisos (ADR-006), exchanges declarados al arrancar y tope de mensaje
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/usuarios.sh", 0755), "/opt/lume/usuarios.sh")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/10-lume.conf", 0644), "/etc/rabbitmq/conf.d/10-lume.conf")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/definiciones.json", 0644), "/etc/rabbitmq/definiciones.json")
                // Con definiciones al arrancar no existe el usuario por defecto: usuarios.sh crea al administrador con estas variables
                .withAdminUser(ADMIN_USUARIO).withAdminPassword(ADMIN_CLAVE)
                // El mismo command del compose: gosu hace que usuarios.sh corra como rabbitmq
                .withCommand("sh", "-c", "gosu rabbitmq /opt/lume/usuarios.sh & exec docker-entrypoint.sh rabbitmq-server")
                // Con varias sesiones probando a la vez, el broker puede tardar más del minuto por omisión en decir «Server startup complete»
                .withStartupTimeout(java.time.Duration.ofMinutes(5));
        for (String svc : new String[]{"SEGURIDAD", "VISITAS", "REPORTES", "NOTIFICACIONES", "GATEWAY"}) {
            c.withEnv(svc + "_RABBIT_CLAVE", svc.toLowerCase() + "-rabbit-solo-prueba");
        }
        c.start();
        // Con el equipo cargado cada rabbitmqctl tarda segundos: 480 s de margen para crear todos los usuarios
        long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(480);
        while (c.execInContainer("test", "-f", "/var/lib/rabbitmq/.usuarios-lume-listos").getExitCode() != 0) {
            if (System.nanoTime() > limite) {
                c.stop(); // no queda un broker huérfano; el siguiente contexto lo intenta de nuevo
                throw new IllegalStateException("usuarios.sh no creó los usuarios de RabbitMQ a tiempo");
            }
            Thread.sleep(500);
        }
        rabbitmqCompartido = c;
        return c;
    }

    @Bean
    DynamicPropertyRegistrar propiedadesRabbitmq(RabbitMQContainer rabbitmq) {
        return r -> {
            r.add("spring.rabbitmq.host", rabbitmq::getHost);
            r.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
            r.add("spring.rabbitmq.username", () -> "visitas");
            r.add("spring.rabbitmq.password", () -> CLAVE_RABBIT);
        };
    }
}
