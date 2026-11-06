package co.com.agsamericas.digsa.seguridad.identidad;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Captcha Cloudflare Turnstile en el ingreso y en «olvidé mi contraseña» (portado de {@code identidad.Captcha} del monolito). Sin clave
 * secreta solo se da por superado si {@code lume.captcha.permitir-sin-clave} (perfiles {@code local} y {@code prueba}); en cualquier otro
 * entorno falla cerrado: nadie ingresa hasta configurarlo. En local y en e2e se usan las claves de prueba públicas de Cloudflare
 * (ADR-014); nunca claves reales en el repo.
 */
@Component
public class Captcha {

    private static final Logger LOG = LoggerFactory.getLogger(Captcha.class);
    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final PropiedadesSeguridad.CaptchaProps cfg;
    private final RestClient http;

    public Captcha(PropiedadesSeguridad cfg) {
        this.cfg = cfg.captcha();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public String claveSitio() {
        return cfg.sitio();
    }

    /** ¿Está desactivado? Solo donde se permite (local, pruebas) y sin clave secreta. */
    public boolean desactivado() {
        return cfg.secreto() == null && cfg.permitirSinClave();
    }

    public boolean superado(String respuesta, String ip) {
        if (cfg.secreto() == null) {
            return cfg.permitirSinClave();
        }
        if (respuesta == null || respuesta.isBlank() || respuesta.length() > 2048) {
            return false;
        }
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", cfg.secreto());
            form.add("response", respuesta);
            if (ip != null) {
                form.add("remoteip", ip);
            }
            Map<?, ?> r = http.post().uri(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            boolean ok = r != null && Boolean.TRUE.equals(r.get("success"));
            if (!ok && r != null && String.valueOf(r.get("error-codes")).matches(".*(invalid-input-secret|missing-input-secret|invalid-sitekey).*")) {
                // Error de configuración, no del usuario: sin esto nadie ingresa y nada explica por qué
                LOG.error("Captcha mal configurado (Cloudflare Turnstile): {}", r.get("error-codes"));
            }
            return ok;
        } catch (RuntimeException e) {
            LOG.warn("No se pudo verificar el captcha: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
