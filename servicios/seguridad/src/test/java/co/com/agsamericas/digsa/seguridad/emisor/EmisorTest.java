package co.com.agsamericas.digsa.seguridad.emisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Valores solo de prueba (ficticios), no son secretos de ningún entorno. */
@SpringBootTest(properties = {
        "LUME_GATEWAY_SECRETO_CODIFICADO={noop}gateway-solo-prueba",
        "LUME_HUMO_SECRETO_CODIFICADO={noop}humo-solo-prueba",
        "LUME_USUARIO_DEV=dev.prueba",
        "LUME_CLAVE_DEV=Clave-Solo-Prueba-1"})
class EmisorTest {

    @Autowired
    WebApplicationContext contexto;

    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    @Test
    void laSaludEsPublicaYElRestoPideIngreso() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/cualquier-cosa").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
    }

    @Test
    void elEmisorEsLaUrlPublicaDelGateway() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:9000"));
    }

    @Test
    void clientCredentialsEmiteUnTokenConAudienciaLume() throws Exception {
        String basico = Base64.getEncoder().encodeToString("humo:humo-solo-prueba".getBytes(StandardCharsets.UTF_8));
        String cuerpo = mvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basico)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&scope=lume.humo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonMapper json = JsonMapper.builder().build();
        String token = json.readTree(cuerpo).get("access_token").asString();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims.get("aud").toString()).contains("lume");
        assertThat(claims.get("scope").toString()).contains("lume.humo");
        assertThat(claims.has("perfiles")).isFalse();
    }

    @Test
    void unSecretoEquivocadoNoRecibeToken() throws Exception {
        String basico = Base64.getEncoder().encodeToString("humo:otro".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basico)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void autorizarSinSesionLlevaAlIngreso() throws Exception {
        // El emisor lee estos parámetros de la query string (no de param() de MockMvc).
        mvc.perform(get("/oauth2/authorize?response_type=code&client_id=gateway&scope=openid"
                        + "&redirect_uri=http://localhost:9000/bff/login/oauth2/code/lume"
                        + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256")
                        .accept(MediaType.TEXT_HTML)) // como un navegador: sin text/html el emisor responde 401
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login"));
    }
}
