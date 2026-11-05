package co.com.agsamericas.digsa.seguridad.soporte;


import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un navegador frente a {@code seguridad}: conserva las cookies (la de la sesión del emisor, {@code LUME_IDP}) entre peticiones, como
 * hace el gateway con {@code /api/auth/**} (ADR-014). Las rutas de sesión que van con token se prueban aparte con {@code jwt()}.
 */
public class Navegador {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private String ip = "203.0.113.10"; // TEST-NET-3 (RFC 5737): nunca una IP real

    public Navegador(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Navegador desde(String ip) {
        this.ip = ip;
        return this;
    }

    public ResultActions get(String ruta) throws Exception {
        return hacer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ruta));
    }

    /** GET de navegación (Accept: text/html), como cuando el navegador sigue una redirección. */
    public ResultActions navegar(String ruta) throws Exception {
        return hacer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ruta).accept(MediaType.TEXT_HTML));
    }

    public ResultActions post(String ruta, Object cuerpo) throws Exception {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ruta)
                .contentType(MediaType.APPLICATION_JSON);
        if (cuerpo != null) {
            b.content(cuerpo instanceof String s ? s : JSON.writeValueAsString(cuerpo));
        }
        return hacer(b);
    }

    public ResultActions post(String ruta) throws Exception {
        return post(ruta, null);
    }

    /** ¿Tiene la cookie de la sesión del emisor? */
    public boolean tieneSesionEmisor() {
        return cookies.containsKey("LUME_IDP");
    }

    public void olvidarCookies() {
        cookies.clear();
    }

    private ResultActions hacer(MockHttpServletRequestBuilder b) throws Exception {
        b.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        if (!cookies.isEmpty()) {
            b.cookie(cookies.values().toArray(Cookie[]::new));
        }
        ResultActions r = mvc.perform(b);
        MvcResult res = r.andReturn();
        for (Cookie c : res.getResponse().getCookies()) {
            if (c.getMaxAge() == 0) {
                cookies.remove(c.getName());
            } else {
                cookies.put(c.getName(), c);
            }
        }
        return r;
    }

    /** Lee un campo de texto de una respuesta JSON. */
    public static String campo(ResultActions r, String nombre) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString()).get(nombre).asString();
    }
}
