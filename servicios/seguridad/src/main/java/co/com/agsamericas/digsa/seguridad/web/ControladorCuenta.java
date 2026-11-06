package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.comun.seguridad.ClaimsLume;
import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.seguridad.api.CuentaApi;
import co.com.agsamericas.digsa.seguridad.api.modelo.CambioClave;
import co.com.agsamericas.digsa.seguridad.api.modelo.Mensaje;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioClaves;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioEstadoSesion;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/cuenta/clave}: «Cambiar mi contraseña» (condición 12), como {@code identidad.ControladorCuenta} del monolito. Llega por
 * el gateway con el token del usuario (servidor de recursos de lume-comun).
 */
@RestController
class ControladorCuenta implements CuentaApi {

    private final ServicioClaves claves;
    private final ServicioEstadoSesion estados;
    private final HttpServletRequest req;

    ControladorCuenta(ServicioClaves claves, ServicioEstadoSesion estados, HttpServletRequest req) {
        this.claves = claves;
        this.estados = estados;
        this.req = req;
    }

    @Override
    public ResponseEntity<Mensaje> cambiarMiClave(CambioClave c) {
        String actual = Validar.obligatorio(c.getActual(), "actual", "Escriba su contraseña actual.", 200);
        String clave = Validar.obligatorio(c.getClave(), "clave", "Escriba la contraseña nueva.", 200);
        UsuarioActual u = UsuarioActual.actual().filter(x -> x.uid() != null).orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED,
                "La sesión expiró. Vuelva a iniciar sesión.", java.util.Map.of("motivo", "sin_sesion")));
        // Como el FiltroSesion del monolito: la sesión del token debe seguir abierta (un token de hasta 5 min ya cerrado no sirve)
        ServicioEstadoSesion.Estado e = estados.evaluar(sid());
        if (!e.vigente()) {
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, e.mensaje(), java.util.Map.of("motivo", e.motivo()));
        }
        claves.cambiarPropia(u.uid(), sid(), actual, clave, c.getConfirmacion(), req.getRemoteAddr());
        return ResponseEntity.ok(new Mensaje("Su contraseña se cambió. La próxima vez ingrese con la contraseña nueva."));
    }

    /** El {@code sid} del token, si viene y es válido. */
    static UUID sid() {
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
