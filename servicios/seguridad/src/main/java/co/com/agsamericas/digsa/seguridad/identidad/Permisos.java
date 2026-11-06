package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.comun.seguridad.ClaimsLume;
import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.reglas.Pantallas;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Quién hace la petición y si puede usar una pantalla, revisado contra la base y no contra el token (como el {@code FiltroSesion} y los
 * {@code @PreAuthorize("@permisos.pantalla(...)")} del monolito, que recalculaban perfiles y pantallas en cada petición): un perfil o un
 * rol quitado por el administrador deja de valer en la siguiente petición, sin esperar a que venza el token (5 min).
 */
@Component
public class Permisos {

    /** Quien hace la petición (para la bitácora y las reglas de autoprotección). */
    public record Quien(long id, String usuario) {
    }

    static final String SIN_PERMISO = "No tiene permiso para esta acción.";

    private final ServicioEstadoSesion estados;
    private final RepositorioUsuarios usuarios;

    public Permisos(ServicioEstadoSesion estados, RepositorioUsuarios usuarios) {
        this.estados = estados;
        this.usuarios = usuarios;
    }

    /**
     * La sesión del token debe seguir abierta (401 con su motivo si no) y el usuario debe tener la pantalla (403 si no).
     *
     * @return quién hace la petición
     */
    public Quien exigirPantalla(Pantallas pantalla) {
        UsuarioActual u = UsuarioActual.actual().filter(x -> x.uid() != null)
                .orElseThrow(() -> noAutenticado("sin_sesion", ServicioEstadoSesion.MENSAJES.get("sin_sesion")));
        ServicioEstadoSesion.Estado e = estados.evaluar(sid());
        if (!e.vigente() || e.uid() == null || e.uid().longValue() != u.uid()) {
            throw noAutenticado(e.vigente() ? "sin_sesion" : e.motivo(), e.vigente() ? ServicioEstadoSesion.MENSAJES.get("sin_sesion") : e.mensaje());
        }
        Usuario actual = usuarios.porId(u.uid()).filter(Usuario::activo)
                .orElseThrow(() -> noAutenticado("inactivo", ServicioEstadoSesion.MENSAJES.get("inactivo")));
        if (!actual.pantallas().contains(pantalla)) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, SIN_PERMISO);
        }
        return new Quien(actual.id(), actual.usuario());
    }

    private static ErrorNegocio noAutenticado(String motivo, String mensaje) {
        return new ErrorNegocio(HttpStatus.UNAUTHORIZED, mensaje, Map.of("motivo", motivo));
    }

    /** El {@code sid} del token, si viene y es válido. */
    public static UUID sid() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken t && t.getToken().getClaimAsString(ClaimsLume.SID) instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
