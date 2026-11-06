package co.com.agsamericas.digsa.comun.seguridad;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Revisión de seguridad de la integradora sobre VA1: {@code /interno/**} exige un token de máquina. Un token de persona (claim
 * {@code uid}) que además trae el scope requerido recibe 403; uno sin {@code uid} con el scope pasa. Controlador de prueba mínimo
 * en {@code /interno/ping} y {@code /api/ping}; usa la cadena real de {@link ConfiguracionRecurso}.
 */
@SpringJUnitWebConfig(InternoSoloMaquinaTest.Config.class)
class InternoSoloMaquinaTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(ConfiguracionRecurso.class)
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class); // los tokens de las pruebas los inyecta jwt(), no se decodifican
        }

        @Bean
        PruebaInterna pruebaInterna() {
            return new PruebaInterna();
        }
    }

    @RestController
    static class PruebaInterna {

        @GetMapping("/interno/ping")
        @PreAuthorize("hasAuthority('SCOPE_prueba:interno')")
        String interno() {
            return "ok";
        }

        @GetMapping("/api/ping")
        String api() {
            return "ok";
        }
    }

    @Autowired
    WebApplicationContext contexto;

    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    private static RequestPostProcessor token(Object uid, String... scopes) {
        return jwt().jwt(j -> {
            j.claim("scope", Arrays.asList(scopes)).subject("42");
            if (uid != null) {
                j.claim(ClaimsLume.UID, uid);
            }
        }).authorities((Jwt t) -> new ConvertidorJwt().convert(t).getAuthorities().stream().map(a -> (GrantedAuthority) a).toList());
    }

    @Test
    void unTokenDePersonaConElScopeRequeridoRecibe403EnInterno() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/interno/ping").with(token(7L, "prueba:interno")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("No tiene permiso para esta acción."));
    }

    @Test
    void unTokenDeMaquinaConElScopePasaEnInterno() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/interno/ping").with(token(null, "prueba:interno"))).andExpect(status().isOk());
    }

    @Test
    void unTokenDeMaquinaSinElScopeRecibe403EnInterno() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/interno/ping").with(token(null, "otro:scope"))).andExpect(status().isForbidden());
    }

    @Test
    void sinTokenInternoDa401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/interno/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenDeMaquinaSigueSinEntrarALaApiDePersonas() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/ping").with(token(null, "prueba:interno"))).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/ping").with(token(7L))).andExpect(status().isOk());
    }
}
