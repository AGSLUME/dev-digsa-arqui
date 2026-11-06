package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.seguridad.api.SesionApi;
import co.com.agsamericas.digsa.seguridad.api.modelo.EntradaMenu;
import co.com.agsamericas.digsa.seguridad.api.modelo.EstadoSesion;
import co.com.agsamericas.digsa.seguridad.api.modelo.Minutos;
import co.com.agsamericas.digsa.seguridad.api.modelo.Sesion;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioUsuarios;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioEstadoSesion;
import co.com.agsamericas.digsa.seguridad.identidad.Usuario;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/sesion}, {@code /api/sesion/estado} y {@code /api/sesion/mantener} (contrato; {@code identidad.ControladorSesion} del
 * monolito). Llegan por el gateway con el token del usuario. Como el {@code FiltroSesion} del monolito, cada una revalida la sesión del
 * {@code sid} contra la base (además de lo que ya revisa el gateway) y recalcula menú y pantallas desde los perfiles vigentes.
 */
@RestController
class ControladorSesion implements SesionApi {

    private final ServicioEstadoSesion estados;
    private final RepositorioUsuarios usuarios;
    private final Clock reloj;

    ControladorSesion(ServicioEstadoSesion estados, RepositorioUsuarios usuarios, Clock reloj) {
        this.estados = estados;
        this.usuarios = usuarios;
        this.reloj = reloj;
    }

    @Override
    public ResponseEntity<Sesion> datosSesion() {
        ServicioEstadoSesion.Estado e = vigente();
        Usuario u = usuarios.porId(e.uid()).orElseThrow(() -> noVigente("inactivo"));
        Set<Pantallas> permitidas = u.pantallas();
        Map<Integer, String> nombres = usuarios.nombresPerfiles();
        Sesion s = new Sesion(u.id(), u.usuario(), u.nombre(),
                u.asignados().stream().map(id -> nombres.getOrDefault(id, String.valueOf(id))).sorted().toList(),
                Arrays.stream(Pantallas.values()).filter(p -> !p.oculta() && permitidas.contains(p))
                        .map(p -> new EntradaMenu(p.ruta(), p.titulo(), p.grupo() == null ? "" : p.grupo())).toList(),
                Arrays.stream(Pantallas.values()).filter(permitidas::contains).map(Pantallas::ruta).toList(),
                e.minutos());
        return ResponseEntity.ok(s);
    }

    /** No renueva la sesión (no informa actividad). */
    @Override
    public ResponseEntity<EstadoSesion> estadoSesion() {
        ServicioEstadoSesion.Estado e = vigente();
        return ResponseEntity.ok(new EstadoSesion(e.minutos(), e.restantes(reloj.instant())));
    }

    /** «Seguir conectado». */
    @Override
    public ResponseEntity<Minutos> mantenerSesion() {
        ServicioEstadoSesion.Estado e = vigente();
        estados.tocar(ControladorCuenta.sid());
        return ResponseEntity.ok(new Minutos(e.minutos()));
    }

    private ServicioEstadoSesion.Estado vigente() {
        UUID sid = ControladorCuenta.sid();
        ServicioEstadoSesion.Estado e = estados.evaluar(sid);
        if (!e.vigente()) {
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, e.mensaje(), Map.of("motivo", e.motivo()));
        }
        return e;
    }

    private static ErrorNegocio noVigente(String motivo) {
        return new ErrorNegocio(HttpStatus.UNAUTHORIZED, ServicioEstadoSesion.MENSAJES.get(motivo), Map.of("motivo", motivo));
    }
}
