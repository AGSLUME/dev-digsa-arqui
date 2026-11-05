package co.com.agsamericas.digsa.seguridad.soporte;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL de las pruebas de integración: la misma imagen y digest que {@code infra/docker-compose.yml} (ADR-005, ADR-012). Spring
 * reutiliza el contenedor mientras reutiliza el contexto.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContenedoresPrueba {

    public static final DockerImageName POSTGRES = DockerImageName
            .parse("postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES).withDatabaseName("lume");
    }
}
