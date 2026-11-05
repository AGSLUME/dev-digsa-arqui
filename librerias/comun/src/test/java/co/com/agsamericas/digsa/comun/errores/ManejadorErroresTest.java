package co.com.agsamericas.digsa.comun.errores;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ManejadorErroresTest {

    record Datos(@NotBlank(message = "Escriba su usuario.") String usuario) {
    }

    @RestController
    static class Prueba {
        @GetMapping("/negocio")
        String negocio() {
            throw ErrorNegocio.validacion("La fecha no es hábil.", "fecha");
        }

        @GetMapping("/interno")
        String interno() {
            throw new IllegalStateException("detalle interno que no debe salir");
        }

        @GetMapping("/estado")
        String estado() {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "detalle interno");
        }

        @PostMapping("/validar")
        String validar(@Valid @RequestBody Datos d) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Prueba()).setControllerAdvice(new ManejadorErrores()).build();

    @Test
    void errorDeNegocioEsProblemaConMensajeYCampo() throws Exception {
        mvc.perform(get("/negocio"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("La fecha no es hábil."))
                .andExpect(jsonPath("$.mensaje").value("La fecha no es hábil."))
                .andExpect(jsonPath("$.campo").value("fecha"))
                .andExpect(jsonPath("$.type").value("urn:lume:error:422"));
    }

    @Test
    void validacionDevuelveElPrimerCampoConElTextoDelMonolito() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{\"usuario\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.mensaje").value("Escriba su usuario."))
                .andExpect(jsonPath("$.campo").value("usuario"));
    }

    @Test
    void cuerpoIlegibleEs400() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(ManejadorErrores.PETICION_MAL));
    }

    @Test
    void unaExcepcionConEstado4xxConservaSuEstadoYNoEs500() throws Exception {
        mvc.perform(get("/estado"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(ManejadorErrores.PETICION_MAL));
    }

    @Test
    void unTipoDeContenidoNoSoportadoEs415() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void errorInternoNoExponeElDetalle() throws Exception {
        mvc.perform(get("/interno"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.mensaje").value(ManejadorErrores.INTERNO))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("detalle interno"))));
    }
}
