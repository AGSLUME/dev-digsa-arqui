package co.com.agsamericas.digsa.visitas.soporte;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import co.com.agsamericas.digsa.comun.seguridad.ClaimsLume;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base de las pruebas de integración de {@code visitas}: contexto completo, PostgreSQL de Testcontainers (modelo de dos roles) y perfil
 * {@code prueba}. Reemplaza el reloj ({@code java.time.Clock}) por {@link RelojMovible}; cada prueba empieza con el reloj en la hora real.
 * Sin Spring Session: el servicio es un servidor de recursos sin sesión (ADR-003). Los tokens se arman con {@code jwt()}, con los mismos
 * claims que emite {@code seguridad} y que leen {@code ConvertidorJwt} y {@code UsuarioActual}.
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
    protected RelojMovible reloj;
    @Autowired
    protected AuditoriaCapturada auditoria;

    protected MockMvc mvc;

    @BeforeEach
    void prepararBase() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
        reloj.reiniciar();
        auditoria.limpiar();
    }

    /** Token de persona con perfiles directos vacíos: {@code uid}, {@code usuario} ("u" + uid) y las pantallas dadas. */
    protected static RequestPostProcessor token(long uid, String... pantallas) {
        return token(uid, List.of(), null, pantallas);
    }

    /** Token con perfiles directos (nombres exactos del enum Perfil, p. ej. "Coordinador"). */
    protected static RequestPostProcessor tokenConPerfiles(long uid, List<String> perfiles, String... pantallas) {
        return token(uid, perfiles, null, pantallas);
    }

    /**
     * Token completo. {@code alcance} (opcional, puede ser null) es el mapa del claim {@code alcance} como lo emite seguridad:
     * {@code {tipo: fuerza, fuerza: EJC}}, {@code {tipo: regional, regional: 3}} o {@code {tipo: punto, punto: 7}}.
     */
    protected static RequestPostProcessor token(long uid, List<String> perfiles, Map<String, Object> alcance, String... pantallas) {
        return jwt().jwt(j -> {
            j.claim(ClaimsLume.UID, uid)
                    .claim(ClaimsLume.USUARIO, "u" + uid)
                    .claim(ClaimsLume.PERFILES, perfiles)
                    .claim(ClaimsLume.PANTALLAS, Arrays.asList(pantallas));
            if (alcance != null) {
                j.claim(ClaimsLume.ALCANCE, alcance);
            }
        });
    }

    /** Token de máquina (client_credentials): solo {@code scope}, sin {@code uid}. Para {@code /interno/**}. */
    protected static RequestPostProcessor tokenDeMaquina(String... scopes) {
        return jwt().jwt(j -> j.claim("scope", Arrays.asList(scopes)).subject("servicio-prueba"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Dobles {

        /** También es el {@link java.time.Clock} del servicio (lo extiende). */
        @Bean
        @Primary
        RelojMovible relojMovible() {
            return new RelojMovible(Fechas.BOGOTA);
        }

        /** Captura lo que el servicio publica por el puerto {@code PublicadorAuditoria} (reemplaza al publicador por defecto). */
        @Bean
        @Primary
        AuditoriaCapturada auditoriaCapturada() {
            return new AuditoriaCapturada();
        }
    }
}
