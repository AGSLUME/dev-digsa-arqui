package co.com.agsamericas.digsa.seguridad.correo;

import co.com.agsamericas.digsa.reglas.Mfa;
import org.springframework.web.util.HtmlUtils;

/**
 * Correos de la identidad con los textos exactos del monolito ({@code identidad.PlantillasCorreo}: {@code clave_enlace} y
 * {@code mfa_codigo}). Todo valor va escapado. Cada plantilla sale en dos versiones: la que recibe el usuario (con el secreto) y la que se
 * guarda en el registro (con el secreto enmascarado), como {@code EnvioRegistrado} del monolito.
 */
public final class PlantillasCorreo {

    public static final String MFA = "mfa_codigo";
    public static final String ENLACE = "clave_enlace";
    public static final String ASUNTO_MFA = "Código de verificación · Suite DIGSA";

    /** Lo que queda en el registro en lugar del secreto (monolito: {@code PlantillaCorreo.OCULTO} y {@code ENLACE_OCULTO}). */
    static final String OCULTO = "••••••";
    static final String ENLACE_OCULTO = "[enlace de un solo uso: oculto]";

    private PlantillasCorreo() {
    }

    /** El correo con el código de verificación (asunto {@link #ASUNTO_MFA}). */
    public static CorreoSeguridad.Envio codigoMfa(Long usuarioId, String para, String nombre, String codigo) {
        return new CorreoSeguridad.Envio(MFA, usuarioId, para, ASUNTO_MFA, mfa(nombre, codigo), mfa(nombre, OCULTO));
    }

    /** El correo con el enlace para definir la contraseña; el asunto y el motivo dependen del caso (bienvenida, recuperación...). */
    public static CorreoSeguridad.Envio enlaceClave(Long usuarioId, String para, String asunto, String nombre, String motivo, String enlace,
                                                    int minutos) {
        return new CorreoSeguridad.Envio(ENLACE, usuarioId, para, asunto, enlace(nombre, motivo, enlace, minutos),
                enlace(nombre, motivo, ENLACE_OCULTO, minutos));
    }

    private static String mfa(String nombre, String codigo) {
        return "<p>Hola " + e(nombre) + ":</p><p>Su código para ingresar a la Suite DIGSA es:</p>\n"
                + "<p style=\"font-size:28px;letter-spacing:6px;font-weight:bold\">" + e(codigo) + "</p>\n"
                + "<p>Vence en " + Mfa.MINUTOS_VIGENCIA + " minutos. Si usted no está ingresando, cambie su contraseña y avise al administrador.</p>";
    }

    private static String enlace(String nombre, String motivo, String enlace, int minutos) {
        return "<p>Hola " + e(nombre) + ":</p><p>" + e(motivo) + "</p>\n"
                + "<p><a href=\"" + e(enlace) + "\" style=\"background:#0b3d5c;color:#fff;padding:10px 16px;text-decoration:none;"
                + "border-radius:4px\">Definir mi contraseña</a></p>\n"
                + "<p>El enlace sirve una sola vez y vence en " + minutos + " minutos. Si usted no lo pidió, ignore este correo.</p>";
    }

    private static String e(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
