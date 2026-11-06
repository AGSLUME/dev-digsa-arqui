package co.com.agsamericas.digsa.visitas.maestros;

import co.com.agsamericas.digsa.reglas.Reparto;
import co.com.agsamericas.digsa.visitas.api.MaestrosApi;
import co.com.agsamericas.digsa.visitas.api.modelo.Creado;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosAuditor;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosObligacion;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosOpl;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosPunto;
import co.com.agsamericas.digsa.visitas.api.modelo.Estado;
import co.com.agsamericas.digsa.visitas.api.modelo.ListaAuditores;
import co.com.agsamericas.digsa.visitas.api.modelo.Mensaje;
import co.com.agsamericas.digsa.visitas.api.modelo.Obligacion;
import co.com.agsamericas.digsa.visitas.api.modelo.Opl;
import co.com.agsamericas.digsa.visitas.api.modelo.Punto;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/maestros}: puntos, auditores, operador logístico y obligaciones; el Coordinador (y el Administrador), como el monolito. */
@RestController
@PreAuthorize("hasAuthority('PANTALLA_MAESTROS')")
class ControladorMaestros implements MaestrosApi {

    private final ServicioMaestros servicio;

    ControladorMaestros(ServicioMaestros servicio) {
        this.servicio = servicio;
    }

    @Override
    public ResponseEntity<List<Punto>> listarPuntos(String unidad, String fuerza, String texto, Boolean activo) {
        return ResponseEntity.ok(servicio.puntos(unidad, fuerza, texto, activo));
    }

    @Override
    public ResponseEntity<Creado> crearPunto(DatosPunto p) {
        long id = servicio.crearPunto(punto(p));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Creado(id, "Punto creado."));
    }

    @Override
    public ResponseEntity<Mensaje> editarPunto(Long id, DatosPunto p) {
        servicio.editarPunto(id, punto(p));
        return ResponseEntity.ok(new Mensaje("Punto actualizado."));
    }

    @Override
    public ResponseEntity<Mensaje> cambiarEstadoPunto(Long id, Estado e) {
        boolean activo = e != null && Boolean.TRUE.equals(e.getActivo());
        servicio.estadoPunto(id, activo);
        return ResponseEntity.ok(new Mensaje(activo ? "Punto activado." : "Punto inactivado."));
    }

    @Override
    public ResponseEntity<ListaAuditores> listarAuditores() {
        // LinkedHashMap: los roles salen en el orden de Reparto.ROLES, como en el monolito
        return ResponseEntity.ok(new ListaAuditores(servicio.auditores(), new LinkedHashMap<>(Reparto.ROLES)));
    }

    @Override
    public ResponseEntity<Mensaje> editarAuditor(Long id, DatosAuditor a) {
        servicio.editarAuditor(id, a == null ? null : a.getRol(), a == null ? null : a.getMunicipioBase());
        return ResponseEntity.ok(new Mensaje("Auditor actualizado."));
    }

    @Override
    public ResponseEntity<List<Obligacion>> listarObligaciones() {
        return ResponseEntity.ok(servicio.obligaciones());
    }

    @Override
    public ResponseEntity<Creado> crearObligacion(DatosObligacion o) {
        long id = servicio.guardarObligacion(null, obligacion(o));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Creado(id, "Obligación creada."));
    }

    @Override
    public ResponseEntity<Mensaje> editarObligacion(Long id, DatosObligacion o) {
        servicio.guardarObligacion(id, obligacion(o));
        return ResponseEntity.ok(new Mensaje("Obligación actualizada."));
    }

    @Override
    public ResponseEntity<Opl> consultarOpl() {
        return ResponseEntity.ok(servicio.opl());
    }

    @Override
    public ResponseEntity<Mensaje> guardarOpl(DatosOpl o) {
        servicio.guardarOpl(o == null ? new ServicioMaestros.DatosOpl(null, null, null)
                : new ServicioMaestros.DatosOpl(o.getNombre(), o.getNit(), o.getCorreos()));
        return ResponseEntity.ok(new Mensaje("Operador logístico actualizado."));
    }

    private static ServicioMaestros.DatosPunto punto(DatosPunto p) {
        if (p == null) {
            return new ServicioMaestros.DatosPunto(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        return new ServicioMaestros.DatosPunto(p.getSigla(), p.getNombre(), p.getUnidad(), p.getFuerza(), p.getModalidad(), p.getNit(),
                p.getMunicipio(), p.getDireccion(), p.getNivel(), p.getHorario(), p.getContacto(), p.getCorreos(), p.getRegionalId(),
                p.getLlegaDesde(), p.getAuditorAsignado(), p.getExigeQf());
    }

    private static ServicioMaestros.DatosObligacion obligacion(DatosObligacion o) {
        return o == null ? new ServicioMaestros.DatosObligacion(null, null, null, null, null)
                : new ServicioMaestros.DatosObligacion(o.getPrestador(), o.getCodigo(), o.getTexto(), o.getFuente(), o.getActivo());
    }
}
