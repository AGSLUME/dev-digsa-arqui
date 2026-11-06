package co.com.agsamericas.digsa.visitas.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.visitas.soporte.PruebaApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Revisión de seguridad de la integradora sobre VA1: la autorización por pantalla se decide ANTES de leer el cuerpo. Quien no tiene la
 * pantalla recibe 403 aunque el JSON venga mal formado (si no, un 400 revelaría que la ruta existe y cómo se valida); quien sí la tiene
 * recibe el 400 de formato.
 */
class AutorizacionPorRutaTest extends PruebaApi {

    private static final String SIN_PERMISO = "No tiene permiso para esta acción.";
    private static final String MAL_FORMADO = "La petición no tiene el formato esperado.";
    private static final String JSON_ROTO = "{no es json";

    private ResultActions postCrudo(String url, RequestPostProcessor token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).with(token).contentType(MediaType.APPLICATION_JSON).content(JSON_ROTO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/catalogos/fuerzas", "/api/maestros/puntos"})
    void sinLaPantallaUnJsonMalFormadoDa403NoDa400(String url) throws Exception {
        postCrudo(url, token(7_300_000_001L)).andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/catalogos/fuerzas", "/api/maestros/puntos"})
    void conOtraPantallaUnJsonMalFormadoTambienDa403(String url) throws Exception {
        String otra = url.contains("catalogos") ? "MAESTROS" : "CATALOGOS";
        postCrudo(url, token(7_300_000_002L, otra)).andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
    }

    @Test
    void conLaPantallaCatalogosUnJsonMalFormadoDa400() throws Exception {
        postCrudo("/api/catalogos/fuerzas", token(7_300_000_003L, "CATALOGOS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MAL_FORMADO));
    }

    @Test
    void conLaPantallaMaestrosUnJsonMalFormadoDa400() throws Exception {
        postCrudo("/api/maestros/puntos", token(7_300_000_004L, "MAESTROS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MAL_FORMADO));
    }

    @Test
    void sinTokenSigueSiendo401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/catalogos/fuerzas").contentType(MediaType.APPLICATION_JSON).content(JSON_ROTO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void losMunicipiosSiguenAbiertosACualquierPersonaConToken() throws Exception {
        ResultActions r = get("/api/catalogos/municipios?departamento=05", token(7_300_000_005L)).andExpect(status().isOk());
        assertThat(cuerpo(r)).isNotBlank();
    }
}
