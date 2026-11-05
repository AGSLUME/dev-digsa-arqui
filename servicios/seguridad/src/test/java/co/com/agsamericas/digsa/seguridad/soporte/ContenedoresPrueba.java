package co.com.agsamericas.digsa.seguridad.soporte;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
