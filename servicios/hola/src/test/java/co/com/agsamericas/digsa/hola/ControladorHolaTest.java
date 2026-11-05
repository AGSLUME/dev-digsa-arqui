package co.com.agsamericas.digsa.hola;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.comun.seguridad.ConvertidorJwt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ControladorHolaTest {

    @Autowired
    WebApplicationContext contexto;

    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    @Test
    void sinTokenResponde401ConElTextoDelMonolito() throws Exception {
        mvc.perform(get("/api/hola"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.mensaje").value("La sesión expiró. Vuelva a iniciar sesión."))
                .andExpect(jsonPath("$.motivo").value("sin_sesion"));
    }

    @Test
    void conTokenSaludaAlUsuarioDelToken() throws Exception {
        Jwt token = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("7").claim("uid", 7L)
                .claim("usuario", "ana.prueba").claim("nombre", "Ana Prueba").claim("perfiles", List.of("ANALISTA")).build();
        mvc.perform(get("/api/hola").with(authentication(new ConvertidorJwt().convert(token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Hola, Ana Prueba."))
                .andExpect(jsonPath("$.usuario").value("ana.prueba"))
                .andExpect(jsonPath("$.perfiles[0]").value("ANALISTA"));
    }

    /** Un token de máquina (client_credentials, sin uid) no entra a las API de personas. */
    @Test
    void unTokenDeMaquinaNoEntraALaApiDeUsuarios() throws Exception {
        Jwt token = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("humo").claim("scope", "lume.humo").build();
        mvc.perform(get("/api/hola").with(authentication(new ConvertidorJwt().convert(token))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("No tiene permiso para esta acción."));
    }

    /** Lo que no está declarado queda cerrado: ninguna ruta del servicio queda sin regla. */
    @Test
    void unaRutaNoDeclaradaQuedaCerrada() throws Exception {
        Jwt token = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("7").claim("uid", 7L).build();
        mvc.perform(get("/otra-cosa").with(authentication(new ConvertidorJwt().convert(token))))
                .andExpect(status().isForbidden());
    }

    @Test
    void laSaludEsPublica() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
