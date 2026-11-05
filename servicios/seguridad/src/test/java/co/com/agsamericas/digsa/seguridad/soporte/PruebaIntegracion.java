package co.com.agsamericas.digsa.seguridad.soporte;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base de las pruebas de integración de {@code seguridad}: contexto completo, PostgreSQL de Testcontainers y perfil {@code prueba}.
 * Los secretos son valores solo de prueba (ficticios), declarados en {@code application-prueba.yml} de las pruebas.
 */
@SpringBootTest
@ActiveProfiles("prueba")
@Import(ContenedoresPrueba.class)
public abstract class PruebaIntegracion {
}
