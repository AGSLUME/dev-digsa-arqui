package co.com.agsamericas.digsa.hola;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Responde quién es el usuario del token: prueba de punta a punta de gateway, TokenRelay y validación del JWT. */
@RestController
@RequestMapping("/api/hola")
class ControladorHola {

    record Saludo(String mensaje, String usuario, List<String> perfiles) {
    }

    @GetMapping
    Saludo saludar() {
        UsuarioActual u = UsuarioActual.actual()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, "La sesión expiró. Vuelva a iniciar sesión."));
        String quien = u.nombre() != null ? u.nombre() : u.usuario();
        return new Saludo("Hola, " + quien + ".", u.usuario(), u.perfiles());
    }
}
