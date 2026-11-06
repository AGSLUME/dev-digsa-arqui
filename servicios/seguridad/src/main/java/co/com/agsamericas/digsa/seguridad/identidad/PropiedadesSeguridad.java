package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Mfa;
import co.com.agsamericas.digsa.reglas.Sesion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del servicio ({@code lume.*}), equivalentes a {@code digsa.*} del monolito ({@code ConfiguracionDigsa}).
 *
 * @param sesionMinutos     inactividad cuando no hay fila en {@code seg_parametros} (10 por defecto; Anexo 5, 3.2)
 * @param claveVigenciaDias vigencia de la contraseña (30 por defecto)
 * @param urlPublica        URL pública de la SPA (el gateway), para los enlaces de los correos
 * @param mfa               segundo factor
 * @param captcha           Cloudflare Turnstile
 */
@ConfigurationProperties("lume")
public record PropiedadesSeguridad(Integer sesionMinutos, Integer claveVigenciaDias, String urlPublica, MfaProps mfa, CaptchaProps captcha) {

    public PropiedadesSeguridad {
        sesionMinutos = sesionMinutos == null ? Sesion.MINUTOS_DEFECTO : Sesion.minutos(sesionMinutos);
        claveVigenciaDias = claveVigenciaDias == null || claveVigenciaDias < 1 ? Claves.VIGENCIA_DIAS_DEFECTO : claveVigenciaDias;
        urlPublica = urlPublica == null ? "" : urlPublica.replaceAll("/+$", "");
        mfa = mfa == null ? new MfaProps(null) : mfa;
        captcha = captcha == null ? new CaptchaProps(null, null, false) : captcha;
    }

    /** Enlace absoluto a una ruta de la SPA (sin «/» inicial). */
    public String enlace(String ruta) {
        return urlPublica + "/" + ruta;
    }

    /** @param codigosPorHora tope de códigos por usuario y hora (5 por defecto) */
    public record MfaProps(Integer codigosPorHora) {
        public MfaProps {
            codigosPorHora = codigosPorHora == null || codigosPorHora < 1 ? Mfa.CODIGOS_POR_HORA : codigosPorHora;
        }
    }

    /**
     * @param sitio          clave pública de sitio
     * @param secreto        clave secreta (nunca en el repo)
     * @param permitirSinClave sin secreto, ¿se da por superado? Solo en los perfiles {@code local} y {@code prueba}: en cualquier otro
     *                         entorno falla cerrado (nadie ingresa hasta configurarlo), como el monolito
     */
    public record CaptchaProps(String sitio, String secreto, boolean permitirSinClave) {
        public CaptchaProps {
            sitio = sitio == null || sitio.isBlank() ? null : sitio;
            secreto = secreto == null || secreto.isBlank() ? null : secreto;
        }

        @Override
        public String toString() {
            return "CaptchaProps[sitio=" + sitio + ", secreto=" + (secreto == null ? "no" : "***") + ", permitirSinClave=" + permitirSinClave + "]";
        }
    }
}
