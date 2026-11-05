package co.com.agsamericas.digsa.seguridad.correo;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adaptador PROVISIONAL del puerto de correo (D2 de la integradora, 2026-11-05): envía por SMTP (en local, el Mailpit ya instalado) y deja
 * en {@code correos_registro} el registro y el contenido con el secreto enmascarado. Se retira cuando exista {@code notificaciones} (R1).
 *
 * <p>Condiciones de D2: (1) registro + contenido enmascarado, purgado al año; (2) el secreto nunca en un log (aquí solo se registra
 * {@link CorreoSeguridad.Envio#htmlRegistro()} y los errores no llevan el cuerpo); (3) solo destinatarios del dominio permitido
 * ({@code lume.correo.dominio-permitido}, en local {@code seguridad.prueba.local}): un correo a otro dominio no sale y queda como fallido.
 */
@Component
public class CorreoSmtpProvisional implements CorreoSeguridad {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoSmtpProvisional.class);
    private static final int LOTE_PURGA = 1000;

    private final JavaMailSender smtp;
    private final JdbcClient jdbc;
    private final Clock reloj;
    private final String remitente;
    private final String dominioPermitido;

    public CorreoSmtpProvisional(JavaMailSender smtp, JdbcClient jdbc, Clock reloj,
                                 @Value("${lume.correo.remitente:Suite DIGSA <no-responder@seguridad.prueba.local>}") String remitente,
                                 @Value("${lume.correo.dominio-permitido:}") String dominioPermitido) {
        this.smtp = smtp;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.remitente = remitente;
        this.dominioPermitido = dominioPermitido == null ? "" : dominioPermitido.strip().toLowerCase();
    }

    @Override
    public Resultado enviar(Envio envio) {
        Resultado r = despachar(envio);
        registrar(envio, r);
        return r;
    }

    private Resultado despachar(Envio envio) {
        if (dominioPermitido.isEmpty() || !envio.para().toLowerCase().endsWith("@" + dominioPermitido)) {
            // Falla cerrado: sin dominio configurado no sale ningún correo (nunca a un buzón real desde este adaptador)
            LOG.warn("[correo] {} no enviado: destinatario fuera del dominio permitido", envio);
            return Resultado.fallo("Destinatario fuera del dominio permitido");
        }
        try {
            MimeMessage m = smtp.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, false, StandardCharsets.UTF_8.name());
            h.setFrom(remitente);
            h.setTo(envio.para());
            h.setSubject(envio.asunto());
            h.setText(envio.html(), true);
            smtp.send(m);
            return Resultado.bien();
        } catch (MailException | MessagingException e) {
            // Solo la clase y el mensaje del error de transporte: nunca el cuerpo (lleva el secreto)
            LOG.warn("[correo] {} no enviado: {}", envio, e.getClass().getSimpleName());
            return Resultado.fallo("No se pudo conectar con el servidor de correo");
        }
    }

    private void registrar(Envio envio, Resultado r) {
        try {
            jdbc.sql("""
                    INSERT INTO correos_registro (creado, plantilla, usuario_id, destinatario, asunto, cuerpo, estado, error)
                    VALUES (:creado, :p, :u, :d, :a, :c, :e, :err)""")
                    .param("creado", reloj.instant().atOffset(ZoneOffset.UTC)).param("p", envio.plantilla()).param("u", envio.usuarioId())
                    .param("d", cortar(envio.para(), 150)).param("a", cortar(envio.asunto(), 200)).param("c", envio.htmlRegistro())
                    .param("e", r.ok() ? "enviado" : "fallido").param("err", cortar(r.error(), 500)).update();
        } catch (RuntimeException e) {
            LOG.error("[correo] No se pudo registrar {}", envio, e);
        }
    }

    /** Registro y contenido se guardan 1 año (interventoría) y luego se borran, por lotes. */
    @Scheduled(cron = "${lume.correo.purga-cron:0 15 3 * * *}", zone = "America/Bogota")
    public void purgar() {
        var limite = reloj.instant().minus(Duration.ofDays(366)).atOffset(ZoneOffset.UTC);
        int borrados;
        do {
            borrados = jdbc.sql("DELETE FROM correos_registro WHERE id IN (SELECT id FROM correos_registro WHERE creado < :l LIMIT :n)")
                    .param("l", limite).param("n", LOTE_PURGA).update();
        } while (borrados == LOTE_PURGA);
    }

    private static String cortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
