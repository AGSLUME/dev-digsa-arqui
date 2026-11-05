package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.seguridad.api.IngresoApi;
import co.com.agsamericas.digsa.seguridad.api.modelo.CambioInicial;
import co.com.agsamericas.digsa.seguridad.api.modelo.Codigo;
import co.com.agsamericas.digsa.seguridad.api.modelo.EnlaceClave;
import co.com.agsamericas.digsa.seguridad.api.modelo.Ingreso;
import co.com.agsamericas.digsa.seguridad.api.modelo.IngresoCompleto;
import co.com.agsamericas.digsa.seguridad.api.modelo.Inicio;
import co.com.agsamericas.digsa.seguridad.api.modelo.Mensaje;
import co.com.agsamericas.digsa.seguridad.api.modelo.NuevaClave;
import co.com.agsamericas.digsa.seguridad.api.modelo.Olvide;
import co.com.agsamericas.digsa.seguridad.api.modelo.Paso;
import co.com.agsamericas.digsa.seguridad.identidad.Captcha;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioClaves;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioIngreso;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/*} (contrato {@code seguridad.yaml}, ADR-014): ingreso en dos pasos, «olvidé mi contraseña» y definir la contraseña
 * con el enlace. Mismas rutas, cuerpos, códigos y textos que {@code identidad.ControladorIngreso} del monolito. Público: el CSRF y el
 * límite de tasa los aplica el gateway.
 */
@RestController
class ControladorIngreso implements IngresoApi {

    private static final String CSRF = "X-XSRF-TOKEN";

    private final ServicioIngreso ingreso;
    private final ServicioClaves claves;
    private final Captcha captcha;
    private final HttpServletRequest req;
    private final HttpServletResponse res;

    ControladorIngreso(ServicioIngreso ingreso, ServicioClaves claves, Captcha captcha, HttpServletRequest req, HttpServletResponse res) {
        this.ingreso = ingreso;
        this.claves = claves;
        this.captcha = captcha;
        this.req = req;
        this.res = res;
    }

    @Override
    public ResponseEntity<Inicio> inicio() {
        return ResponseEntity.ok(new Inicio(captcha.desactivado(), CSRF).captchaSitio(captcha.claveSitio()));
    }

    @Override
    public ResponseEntity<Paso> ingresar(Ingreso i) {
        String usuario = Validar.obligatorio(i.getUsuario(), "usuario", "Escriba su usuario.", 50);
        String clave = Validar.obligatorio(i.getClave(), "clave", "Escriba su contraseña.", 200);
        return ResponseEntity.ok(paso(ingreso.ingresar(usuario, clave, i.getCaptcha(), req)));
    }

    @Override
    public ResponseEntity<IngresoCompleto> verificarCodigo(Codigo c) {
        String codigo = Validar.obligatorio(c.getCodigo(), "codigo", "Escriba el código.", 10);
        ServicioIngreso.Completo r = ingreso.verificar(codigo.strip(), req, res);
        return ResponseEntity.ok(new IngresoCompleto(r.usuario(), r.nombre(), r.continuar()));
    }

    @Override
    public ResponseEntity<Paso> reenviarCodigo() {
        return ResponseEntity.ok(paso(ingreso.reenviar(req)));
    }

    @Override
    public ResponseEntity<Paso> cambiarClaveEnIngreso(CambioInicial c) {
        String clave = Validar.obligatorio(c.getClave(), "clave", "Escriba la contraseña.", 200);
        return ResponseEntity.ok(paso(ingreso.cambiarClaveEnIngreso(clave, c.getConfirmacion(), req)));
    }

    @Override
    public ResponseEntity<Mensaje> olvide(Olvide o) {
        String valor = Validar.obligatorio(o.getValor(), "valor", "Escriba su usuario o su correo.", 150);
        if (!captcha.superado(o.getCaptcha(), req.getRemoteAddr())) {
            throw ErrorNegocio.validacion("Por favor complete la verificación de seguridad (captcha).");
        }
        claves.olvide(valor);
        return ResponseEntity.ok(new Mensaje(
                "Si el usuario o el correo están registrados y activos, le enviamos un enlace para definir una contraseña nueva."));
    }

    @Override
    public ResponseEntity<EnlaceClave> consultarEnlace(String tkn) {
        return ResponseEntity.ok(claves.usuarioDelEnlace(tkn)
                .map(u -> new EnlaceClave(true).nombre(u.nombre()))
                .orElseGet(() -> new EnlaceClave(false)
                        .mensaje("El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».")));
    }

    @Override
    public ResponseEntity<Mensaje> definirClave(NuevaClave n) {
        String tkn = Validar.obligatorio(n.getTkn(), "tkn", "El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».", 64);
        String clave = Validar.obligatorio(n.getClave(), "clave", "Escriba la contraseña.", 200);
        claves.definir(tkn, clave, n.getConfirmacion());
        return ResponseEntity.ok(new Mensaje("La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña."));
    }

    private static Paso paso(ServicioIngreso.Paso p) {
        return new Paso(Paso.PasoEnum.fromValue(p.paso()), p.mensaje()).correo(p.correo());
    }
}
