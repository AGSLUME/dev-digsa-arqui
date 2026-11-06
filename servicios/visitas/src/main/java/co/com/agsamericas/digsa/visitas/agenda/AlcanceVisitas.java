package co.com.agsamericas.digsa.visitas.agenda;

import co.com.agsamericas.digsa.reglas.Alcance;
import java.util.Map;

/**
 * Condición SQL del alcance de datos sobre «v» (visitas) y «p» (puntos), como {@code ServicioConsultaAgenda.condicion} del monolito. La usan
 * el calendario, las solicitudes y, en VISITAS B, OPL, hallazgos y tablero. Falla cerrado: {@link Alcance.Ninguno} no ve nada.
 */
public final class AlcanceVisitas {

    /** La visita a necesidad es sin previo aviso (Anexo 3 p.14): ni el OPL ni el ESM la ven mientras no se haga. */
    public static final String SIN_NECESIDAD_PENDIENTE = "NOT (v.tipo = 'necesidad' AND v.estado IN ('programada', 'notificada', 'reprogramada'))";

    /** Condición y sus parámetros (con nombres {@code alc_*} para no chocar con los de quien la usa). */
    public record Condicion(String sql, Map<String, Object> params) {
    }

    private AlcanceVisitas() {
    }

    public static Condicion condicion(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> new Condicion("true", Map.of());
            // OPL nacional: no ve las visitas a necesidad que aún no se hacen (son sin previo aviso, Anexo 3 p.14)
            case Alcance.Opl o -> new Condicion(SIN_NECESIDAD_PENDIENTE, Map.of());
            case Alcance.Fuerza f -> new Condicion("p.fuerza = :alc_fuerza", Map.of("alc_fuerza", f.codigo()));
            case Alcance.Regional r -> new Condicion("p.regional_id = :alc_regional AND " + SIN_NECESIDAD_PENDIENTE, Map.of("alc_regional", r.id()));
            case Alcance.Esm e -> new Condicion("v.punto_id = :alc_punto AND " + SIN_NECESIDAD_PENDIENTE, Map.of("alc_punto", e.puntoId()));
            case Alcance.Equipo e -> new Condicion("EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = :alc_usuario)",
                    Map.of("alc_usuario", e.usuarioId()));
            case Alcance.Ninguno n -> new Condicion("false", Map.of());
        };
    }
}
