package co.com.agsamericas.digsa.comun.errores;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Cuerpo de error único de LUME: RFC 7807 ({@code application/problem+json}) con las extensiones que ya usa la interfaz del monolito.
 * {@code mensaje} repite {@code detail} (las pantallas y los e2e leen {@code mensaje}); {@code campo} y {@code motivo} van cuando aplican.
 * Nunca lleva el detalle interno de una excepción.
 */
public final class Problemas {

    /** Base de los {@code type}: identifica el error, no es una URL que haya que resolver. */
    public static final String TIPO_BASE = "urn:lume:error:";

    private Problemas() {
    }

    public static ProblemDetail de(HttpStatusCode estado, String mensaje) {
        return de(estado, mensaje, Map.of());
    }

    public static ProblemDetail de(HttpStatusCode estado, String mensaje, Map<String, Object> datos) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, mensaje);
        p.setType(URI.create(TIPO_BASE + estado.value()));
        p.setProperty("mensaje", mensaje);
        datos.forEach(p::setProperty);
        return p;
    }

    /**
     * El problema como mapa plano (miembros RFC 7807 + extensiones al mismo nivel), para escribirlo fuera de Spring MVC (filtros de
     * seguridad) sin depender del mixin de Jackson que registra el convertidor de mensajes.
     */
    public static Map<String, Object> comoMapa(ProblemDetail p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (p.getType() != null) {
            m.put("type", p.getType().toString());
        }
        m.put("title", p.getTitle());
        m.put("status", p.getStatus());
        m.put("detail", p.getDetail());
        if (p.getInstance() != null) {
            m.put("instance", p.getInstance().toString());
        }
        if (p.getProperties() != null) {
            m.putAll(p.getProperties());
        }
        return m;
    }
}
