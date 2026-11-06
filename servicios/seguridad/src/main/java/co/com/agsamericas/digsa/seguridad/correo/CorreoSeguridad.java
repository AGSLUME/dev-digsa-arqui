package co.com.agsamericas.digsa.seguridad.correo;

import java.util.Objects;

/**
 * Puerto de salida de los correos de seguridad: código MFA y enlaces para definir la contraseña (ADR-004; D2 de la integradora).
 * Dos adaptadores: el definitivo llama a {@code notificaciones} (cuando exista, R1) y el provisional envía por SMTP a Mailpit.
 *
 * <p>Regla de los dos: el secreto (código o enlace) solo va en {@link Envio#html()}, que se entrega al destinatario. Lo que se guarda en
 * el registro (1 año) es {@link Envio#htmlRegistro()}, con el secreto enmascarado. Ningún adaptador escribe el secreto en un log.
 */
public interface CorreoSeguridad {

    /** Envía el correo y deja su registro. Nunca lanza por un fallo de envío: lo informa en el resultado. */
    Resultado enviar(Envio envio);

    /**
     * @param plantilla    nombre estable de la plantilla ({@code mfa_codigo}, {@code clave_enlace})
     * @param usuarioId    usuario al que se refiere, para el registro
     * @param para         correo del destinatario
     * @param asunto       asunto
     * @param html         cuerpo con el secreto (solo se envía)
     * @param htmlRegistro cuerpo con el secreto enmascarado (se guarda)
     */
    record Envio(String plantilla, Long usuarioId, String para, String asunto, String html, String htmlRegistro) {
        public Envio {
            Objects.requireNonNull(plantilla, "plantilla");
            Objects.requireNonNull(para, "para");
            Objects.requireNonNull(asunto, "asunto");
            Objects.requireNonNull(html, "html");
            Objects.requireNonNull(htmlRegistro, "htmlRegistro");
        }

        /** Sin el cuerpo: lo único que se puede imprimir de un envío. */
        @Override
        public String toString() {
            return "Envio[plantilla=" + plantilla + ", usuarioId=" + usuarioId + "]";
        }
    }

    /** @param error causa legible (sin el secreto) cuando {@code ok} es falso */
    record Resultado(boolean ok, String error) {
        public static Resultado bien() {
            return new Resultado(true, null);
        }

        public static Resultado fallo(String error) {
            return new Resultado(false, error);
        }
    }
}
