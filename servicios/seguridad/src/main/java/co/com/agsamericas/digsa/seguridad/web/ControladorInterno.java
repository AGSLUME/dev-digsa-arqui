package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.seguridad.api.InternoApi;
import co.com.agsamericas.digsa.seguridad.api.modelo.CierreInterno;
import co.com.agsamericas.digsa.seguridad.api.modelo.EstadoInterno;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioEstadoSesion;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /interno/sesiones/**}: el gateway consulta el estado de la sesión en cada petición (caché ≤30 s), informa la actividad y cierra
 * por salida o inactividad (ADR-004). Solo red interna y solo con un token de máquina con el alcance {@code seguridad:sesiones}; la cadena
 * de recurso de lume-comun ya exige un token en {@code /interno/**}.
 */
@RestController
// Token de máquina (sin uid) con el alcance: un token de usuario nunca entra aunque trajera el alcance
@PreAuthorize("hasAuthority('SCOPE_seguridad:sesiones') and principal?.claims?.get('uid') == null")
class ControladorInterno implements InternoApi {

    private final ServicioEstadoSesion estados;

    ControladorInterno(ServicioEstadoSesion estados) {
        this.estados = estados;
    }

    @Override
    public ResponseEntity<EstadoInterno> estadoSesionInterno(UUID sid) {
        ServicioEstadoSesion.Estado e = estados.evaluar(sid);
        EstadoInterno r = new EstadoInterno(e.vigente(), e.minutos()).uid(e.uid());
        if (!e.vigente()) {
            r.motivo(EstadoInterno.MotivoEnum.fromValue(e.motivo())).mensaje(e.mensaje());
        }
        return ResponseEntity.ok(r);
    }

    @Override
    public ResponseEntity<Void> actividadSesionInterno(UUID sid) {
        estados.tocar(sid);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> cerrarSesionInterno(UUID sid, CierreInterno c) {
        if (c == null || c.getMotivo() == null) {
            throw ErrorNegocio.validacion("Motivo no válido.", "motivo");
        }
        estados.cerrarPorGateway(sid, c.getMotivo() == CierreInterno.MotivoEnum.INACTIVIDAD ? Motivo.INACTIVIDAD : Motivo.SALIDA);
        return ResponseEntity.noContent().build();
    }
}
