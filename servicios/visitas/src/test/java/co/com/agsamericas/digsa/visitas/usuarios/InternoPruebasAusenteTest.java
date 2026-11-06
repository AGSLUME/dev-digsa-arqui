package co.com.agsamericas.digsa.visitas.usuarios;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.visitas.soporte.ContenedoresPrueba;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * VA1.5: fuera de los perfiles {@code local} y {@code prueba} el controlador de {@code /interno/pruebas/usuarios} no existe, aunque
 * el token de máquina sea válido y traiga el alcance {@code visitas:pruebas}: la copia de usuarios solo la escriben los eventos de seguridad.
 */
@SpringBootTest(properties = {"LUME_ARCHIVOS_VISITAS=${java.io.tmpdir}/lume-pruebas/visitas/archivos",
        // Comparte PostgreSQL y RabbitMQ con las demás pruebas: su despachador no publica en segundo plano lo que dejan ellas
        "lume.eventos.cada-ms=0",
        // ni compite con el otro contexto por la cola visitas.usuarios
        "spring.rabbitmq.listener.simple.auto-startup=false"})
@ActiveProfiles("otro")
@Import(ContenedoresPrueba.class)
class InternoPruebasAusenteTest {

    @Autowired
    WebApplicationContext contexto;

    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("POST /interno/pruebas/usuarios con token de máquina válido responde 404")
    void elEndpointNoExisteFueraDeLocalYPrueba() throws Exception {
        mvc.perform(post("/interno/pruebas/usuarios")
                        .with(jwt().jwt(j -> j.claim("scope", List.of("visitas:pruebas")).subject("servicio-prueba")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":8999999999,\"usuario\":\"u8999999999\",\"nombre\":\"No debe existir\",\"documento\":null,"
                                + "\"correo\":null,\"activo\":true,\"perfiles\":[3],\"alcanceFuerza\":null,\"alcancePunto\":null,"
                                + "\"alcanceRegional\":null}"))
                .andExpect(status().isNotFound());
    }
}
