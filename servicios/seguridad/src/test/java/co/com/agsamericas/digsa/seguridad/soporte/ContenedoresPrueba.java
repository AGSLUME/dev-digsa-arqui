package co.com.agsamericas.digsa.seguridad.soporte;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL de las pruebas de integración: la misma imagen y digest que {@code infra/docker-compose.yml} (ADR-005, ADR-012) y el mismo
 * script de arranque {@code infra/postgres/init/01-esquemas.sh}. Así las pruebas corren con el modelo de dos roles de D8: Flyway con
 * {@code seguridad_ddl} y la aplicación con {@code seguridad}, que solo tiene los GRANT de cada migración (un GRANT faltante hace fallar
 * la prueba, como fallaría en local y en producción). Las claves son ficticias, solo de prueba.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContenedoresPrueba {

    public static final DockerImageName POSTGRES = DockerImageName
            .parse("postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32")
            .asCompatibleSubstituteFor("postgres");

    public static final DockerImageName RABBITMQ = DockerImageName
            .parse("rabbitmq:4.3.7-management@sha256:6e51f8f18e055d6dc3d94316db0073da64d811c2e19744661fae06b03c1d6fa8")
            .asCompatibleSubstituteFor("rabbitmq");

    private static final String CLAVE_APP = "seguridad-app-solo-prueba";
    private static final String CLAVE_DDL = "seguridad-ddl-solo-prueba";

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        PostgreSQLContainer c = new PostgreSQLContainer(POSTGRES)
                .withDatabaseName("lume")
                .withUsername("lume_admin")
                .withPassword("admin-solo-prueba")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/postgres/init/01-esquemas.sh", 0755),
                        "/docker-entrypoint-initdb.d/01-esquemas.sh");
        for (String svc : new String[]{"SEGURIDAD", "VISITAS", "REPORTES", "NOTIFICACIONES"}) {
            c.withEnv(svc + "_DB_CLAVE", svc.toLowerCase() + "-app-solo-prueba");
            c.withEnv(svc + "_DB_CLAVE_DDL", svc.toLowerCase() + "-ddl-solo-prueba");
        }
        return c;
    }

    /** Clave ficticia del usuario de RabbitMQ {@code seguridad} (solo pruebas). */
    /** Administrador de RabbitMQ de las pruebas (solo la plomería: colas de lectura que enlazan «#»). Clave ficticia. */
    public static final String ADMIN_USUARIO = "lume_admin";
    public static final String ADMIN_CLAVE = "admin-rabbit-solo-prueba";
    private static final String CLAVE_RABBIT = "seguridad-rabbit-solo-prueba";

    /**
     * RabbitMQ de las pruebas de eventos: la misma imagen y digest que {@code infra/docker-compose.yml} y el mismo arranque:
     * {@code infra/rabbitmq/usuarios.sh} crea un usuario por servicio con permisos mínimos (claves ficticias). La aplicación se conecta
     * como {@code seguridad}, no como administrador. Se espera a que el script termine ({@code .usuarios-lume-listos}).
     */
    @Bean(destroyMethod = "stop")
    RabbitMQContainer rabbitmq() throws Exception {
        RabbitMQContainer c = new RabbitMQContainer(RABBITMQ)
                // Los mismos tres archivos que el compose: matriz de permisos (ADR-006), exchanges declarados al arrancar y tope de mensaje
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/usuarios.sh", 0755), "/opt/lume/usuarios.sh")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/10-lume.conf", 0644), "/etc/rabbitmq/conf.d/10-lume.conf")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/definiciones.json", 0644), "/etc/rabbitmq/definiciones.json")
                // Con definiciones al arrancar no existe el usuario por defecto: usuarios.sh crea al administrador con estas variables
                .withAdminUser(ADMIN_USUARIO).withAdminPassword(ADMIN_CLAVE)
                // OJO: con "/opt/lume/usuarios.sh &" a secas (como el compose) el script corre como root, crea .erlang.cookie con dueño root y el nodo
                // (usuario rabbitmq) no arranca (eacces). Aquí el script corre como rabbitmq con gosu.
                .withCommand("sh", "-c", "gosu rabbitmq /opt/lume/usuarios.sh & exec docker-entrypoint.sh rabbitmq-server");
        for (String svc : new String[]{"SEGURIDAD", "VISITAS", "REPORTES", "NOTIFICACIONES", "GATEWAY"}) {
            c.withEnv(svc + "_RABBIT_CLAVE", svc.toLowerCase() + "-rabbit-solo-prueba");
        }
        c.start();
        long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
        while (c.execInContainer("test", "-f", "/var/lib/rabbitmq/.usuarios-lume-listos").getExitCode() != 0) {
            if (System.nanoTime() > limite) {
                throw new IllegalStateException("usuarios.sh no creó los usuarios de RabbitMQ a tiempo");
            }
            Thread.sleep(500);
        }
        return c;
    }

    @Bean
    DynamicPropertyRegistrar propiedadesRabbitmq(RabbitMQContainer rabbitmq) {
        return r -> {
            r.add("spring.rabbitmq.host", rabbitmq::getHost);
            r.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
            r.add("spring.rabbitmq.username", () -> "seguridad");
            r.add("spring.rabbitmq.password", () -> CLAVE_RABBIT);
        };
    }

    @Bean
    DynamicPropertyRegistrar propiedadesPostgres(PostgreSQLContainer postgres) {
        return r -> {
            r.add("spring.datasource.url", postgres::getJdbcUrl);
            r.add("spring.datasource.username", () -> "seguridad");
            r.add("spring.datasource.password", () -> CLAVE_APP);
            r.add("spring.flyway.user", () -> "seguridad_ddl");
            r.add("spring.flyway.password", () -> CLAVE_DDL);
        };
    }
}
