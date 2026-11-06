package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.Navegador;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Revisión de seguridad de S1 (M1, M2): intentos en paralelo no evaden el tope de 5 del código MFA ni el bloqueo 5/15 de la contraseña
 * (el intento se reserva de forma atómica antes de comparar).
 */
class ConcurrenciaIngresoTest extends PruebaIntegracion {

    private static final int EN_PARALELO = 20;

    @Test
    @DisplayName("M2: 20 contraseñas erradas en paralelo hacen como máximo 5 comparaciones y dejan la cuenta bloqueada")
    void contrasenasEnParaleloNoEvadenElBloqueo() throws Exception {
        var u = datos.usuario();
        List<Integer> estados = enParalelo(i -> mvc.perform(post("/api/auth/ingreso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"" + u.usuario() + "\",\"clave\":\"Errada.Paralela." + i + "\"}"))
                .andReturn().getResponse().getStatus());
        assertThat(estados).containsOnly(401);
        assertThat(jdbc.sql("SELECT intentos FROM usuarios WHERE id = :id").param("id", u.id()).query(Integer.class).single())
                .isLessThanOrEqualTo(5);
        assertThat(jdbc.sql("SELECT bloqueado_hasta > now() FROM usuarios WHERE id = :id").param("id", u.id()).query(Boolean.class).single())
                .isTrue();
        // Con la cuenta bloqueada, ni la contraseña correcta entra ni borra el bloqueo
        navegador().post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE));
        assertThat(jdbc.sql("SELECT bloqueado_hasta > now() FROM usuarios WHERE id = :id").param("id", u.id()).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    @DisplayName("M1: 20 códigos errados en paralelo sobre la misma sesión de ingreso no pasan de 5 intentos; el correcto ya no sirve")
    void codigosEnParaleloNoEvadenLosIntentos() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andReturn();
        Cookie sesion = n.cookie("LUME_IDP");
        assertThat(sesion).isNotNull();
        String correcto = correos.ultimoCodigo(u.correo());
        String errado = correcto.equals("000000") ? "111111" : "000000";
        List<Integer> estados = enParalelo(i -> mvc.perform(post("/api/auth/codigo").cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"" + errado + "\"}")).andReturn().getResponse().getStatus());
        assertThat(estados).containsOnly(422);
        assertThat(jdbc.sql("SELECT max(intentos) FROM mfa_codigos WHERE usuario_id = :u").param("u", u.id()).query(Integer.class).single())
                .isLessThanOrEqualTo(5);
        mvc.perform(post("/api/auth/codigo").cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"" + correcto + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
    }

    private interface Peticion {
        int hacer(int i) throws Exception;
    }

    private static List<Integer> enParalelo(Peticion p) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(EN_PARALELO);
        try {
            List<Future<Integer>> f = new ArrayList<>();
            for (int i = 0; i < EN_PARALELO; i++) {
                int n = i;
                f.add(hilos.submit((Callable<Integer>) () -> p.hacer(n)));
            }
            List<Integer> r = new ArrayList<>();
            for (Future<Integer> x : f) {
                r.add(x.get());
            }
            return r;
        } finally {
            hilos.shutdownNow();
        }
    }
}
