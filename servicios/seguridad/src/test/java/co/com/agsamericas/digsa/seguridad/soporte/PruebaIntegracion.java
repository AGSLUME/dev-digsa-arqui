package co.com.agsamericas.digsa.seguridad.soporte;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import co.com.agsamericas.digsa.reglas.Fechas;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base de las pruebas de integración de {@code seguridad}: contexto completo, PostgreSQL de Testcontainers y perfil {@code prueba}.
 * Reemplaza el correo por {@link CorreosCapturados} y el reloj ({@code java.time.Clock}) por {@link RelojMovible}; cada prueba empieza con el buzón vacío y el
 * reloj en la hora real. Los secretos son valores solo de prueba (ficticios), declarados en {@code application-prueba.yml}.
 */
@SpringBootTest
@ActiveProfiles("prueba")
@Import({ContenedoresPrueba.class, PruebaIntegracion.Dobles.class})
public abstract class PruebaIntegracion {

    @Autowired
    protected WebApplicationContext contexto;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected CorreosCapturados correos;
    @Autowired
    protected RelojMovible reloj;

    protected MockMvc mvc;
    protected DatosPrueba datos;

    @BeforeEach
    void prepararBase() {
        // Con el filtro de Spring Session (sesión del emisor en base, cookie LUME_IDP) antes del de seguridad, como en el servidor real:
        // sin él, MockMvc usaría su propia sesión en memoria y la cookie no la recuperaría.
        mvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(contexto.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                .apply(springSecurity())
                .build();
        correos.limpiar();
        reloj.reiniciar();
        datos = new DatosPrueba(jdbc, reloj);
    }

    /** Un navegador nuevo (sin cookies) frente a {@code seguridad}. */
    protected Navegador navegador() {
        return new Navegador(mvc);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dobles {

        /** También es el puerto {@code CorreoSeguridad} del servicio (lo implementa). */
        @Bean
        @Primary
        CorreosCapturados correosCapturados() {
            return new CorreosCapturados();
        }

        /** También es el {@link Clock} del servicio (lo extiende). */
        @Bean
        @Primary
        RelojMovible relojMovible() {
            return new RelojMovible(Fechas.BOGOTA);
        }
    }
}
