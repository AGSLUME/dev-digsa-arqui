package co.com.agsamericas.digsa.visitas.catalogos;

import co.com.agsamericas.digsa.visitas.api.CatalogosApi;
import co.com.agsamericas.digsa.visitas.api.modelo.Catalogos;
import co.com.agsamericas.digsa.visitas.api.modelo.CodigoNombre;
import co.com.agsamericas.digsa.visitas.api.modelo.Creado;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosFuerza;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosParametro;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosRegional;
import co.com.agsamericas.digsa.visitas.api.modelo.DatosValor;
import co.com.agsamericas.digsa.visitas.api.modelo.Mensaje;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/catalogos}: el Coordinador (y el Administrador) los edita en vivo (Anexo 5, prueba 4), como {@code ControladorCatalogos} del
 * monolito. La pantalla sale del token ({@code PANTALLA_CATALOGOS}); los municipios solo exigen sesión.
 */
@RestController
@PreAuthorize("hasAuthority('PANTALLA_CATALOGOS')")
class ControladorCatalogos implements CatalogosApi {

    private final ServicioCatalogos servicio;

    ControladorCatalogos(ServicioCatalogos servicio) {
        this.servicio = servicio;
    }

    @Override
    public ResponseEntity<Catalogos> listarCatalogos() {
        return ResponseEntity.ok(servicio.todo());
    }

    /** Municipios de un departamento: también los usan los maestros y la agenda. */
    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CodigoNombre>> listarMunicipios(String departamento) {
        return ResponseEntity.ok(servicio.municipios(departamento));
    }

    @Override
    public ResponseEntity<Mensaje> crearFuerza(DatosFuerza f) {
        servicio.crearFuerza(fuerza(f));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Mensaje("Fuerza creada."));
    }

    @Override
    public ResponseEntity<Mensaje> editarFuerza(String codigo, DatosFuerza f) {
        servicio.editarFuerza(codigo, fuerza(f));
        return ResponseEntity.ok(new Mensaje("Fuerza actualizada."));
    }

    @Override
    public ResponseEntity<Mensaje> eliminarFuerza(String codigo) {
        servicio.eliminarFuerza(codigo);
        return ResponseEntity.ok(new Mensaje("Fuerza eliminada."));
    }

    @Override
    public ResponseEntity<Creado> crearValor(String catalogo, DatosValor v) {
        long id = servicio.crearValor(catalogo, valor(v));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Creado(id, "Valor creado."));
    }

    @Override
    public ResponseEntity<Mensaje> editarValor(Long id, DatosValor v) {
        servicio.editarValor(id, valor(v));
        return ResponseEntity.ok(new Mensaje("Valor actualizado."));
    }

    @Override
    public ResponseEntity<Mensaje> eliminarValor(Long id) {
        servicio.eliminarValor(id);
        return ResponseEntity.ok(new Mensaje("Valor eliminado."));
    }

    @Override
    public ResponseEntity<Creado> crearRegional(DatosRegional r) {
        long id = servicio.crearRegional(r == null ? null : r.getNombre());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Creado(id, "Regional creada."));
    }

    @Override
    public ResponseEntity<Mensaje> editarRegional(Long id, DatosRegional r) {
        servicio.editarRegional(id, r == null ? null : r.getNombre(), r == null || r.getActivo() == null || r.getActivo());
        return ResponseEntity.ok(new Mensaje("Regional actualizada."));
    }

    @Override
    public ResponseEntity<Mensaje> editarParametro(String clave, DatosParametro p) {
        servicio.editarParametro(clave, p == null ? null : p.getValor());
        return ResponseEntity.ok(new Mensaje("Parámetro actualizado."));
    }

    private static ServicioCatalogos.DatosFuerza fuerza(DatosFuerza f) {
        return f == null ? new ServicioCatalogos.DatosFuerza(null, null, null, null, null)
                : new ServicioCatalogos.DatosFuerza(f.getCodigo(), f.getNombre(), f.getSanidad(), f.getCorreos(), f.getActivo());
    }

    private static ServicioCatalogos.DatosValor valor(DatosValor v) {
        return v == null ? new ServicioCatalogos.DatosValor(null, null, null)
                : new ServicioCatalogos.DatosValor(v.getCodigo(), v.getDescripcion(), v.getActivo());
    }
}
