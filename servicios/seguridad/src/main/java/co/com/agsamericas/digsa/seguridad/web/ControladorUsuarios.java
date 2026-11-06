package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Sesion;
import co.com.agsamericas.digsa.seguridad.api.UsuariosApi;
import co.com.agsamericas.digsa.seguridad.api.modelo.BaseRol;
import co.com.agsamericas.digsa.seguridad.api.modelo.CambioSeguridad;
import co.com.agsamericas.digsa.seguridad.api.modelo.CatalogoRoles;
import co.com.agsamericas.digsa.seguridad.api.modelo.ClaveInicialNueva;
import co.com.agsamericas.digsa.seguridad.api.modelo.DatosRol;
import co.com.agsamericas.digsa.seguridad.api.modelo.DatosUsuario;
import co.com.agsamericas.digsa.seguridad.api.modelo.EstadoActivo;
import co.com.agsamericas.digsa.seguridad.api.modelo.FilaUsuario;
import co.com.agsamericas.digsa.seguridad.api.modelo.Fuerza;
import co.com.agsamericas.digsa.seguridad.api.modelo.ListaUsuarios;
import co.com.agsamericas.digsa.seguridad.api.modelo.Mensaje;
import co.com.agsamericas.digsa.seguridad.api.modelo.NombrePerfil;
import co.com.agsamericas.digsa.seguridad.api.modelo.PantallaElegible;
import co.com.agsamericas.digsa.seguridad.api.modelo.ParametrosSeguridad;
import co.com.agsamericas.digsa.seguridad.api.modelo.PerfilAsignable;
import co.com.agsamericas.digsa.seguridad.api.modelo.PuntoEsm;
import co.com.agsamericas.digsa.seguridad.api.modelo.Regional;
import co.com.agsamericas.digsa.seguridad.api.modelo.RolAdicional;
import co.com.agsamericas.digsa.seguridad.api.modelo.RolCreado;
import co.com.agsamericas.digsa.seguridad.api.modelo.UsuarioGuardado;
import co.com.agsamericas.digsa.seguridad.identidad.Permisos;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioRoles;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioUsuarios;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/usuarios/**}: administración de usuarios, roles adicionales y parámetros de seguridad (pantalla USUARIOS), como
 * {@code identidad.ControladorUsuarios} del monolito. Cada operación revisa contra la base que la sesión siga abierta y que quien la pide
 * tenga la pantalla ({@link Permisos}), no solo el token.
 */
@RestController
class ControladorUsuarios implements UsuariosApi {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<Fuerza> FUERZAS = List.of(new Fuerza(Fuerza.CodigoEnum.EJC, "DISAN EJC (Ejército)"),
            new Fuerza(Fuerza.CodigoEnum.ARC, "DISAN ARC (Armada)"), new Fuerza(Fuerza.CodigoEnum.FAC, "JEFSA FAC (Fuerza Aeroespacial)"));

    private final ServicioUsuarios servicio;
    private final ServicioRoles roles;
    private final co.com.agsamericas.digsa.seguridad.identidad.ParametrosSeguridad parametros;

    private final Permisos permisos;
    private final Clock reloj;

    ControladorUsuarios(ServicioUsuarios servicio, ServicioRoles roles, co.com.agsamericas.digsa.seguridad.identidad.ParametrosSeguridad parametros,
                        Permisos permisos, Clock reloj) {
        this.servicio = servicio;
        this.roles = roles;
        this.parametros = parametros;

        this.permisos = permisos;
        this.reloj = reloj;
    }

    private Permisos.Quien quien() {
        return permisos.exigirPantalla(Pantallas.USUARIOS);
    }

    @Override
    public ResponseEntity<ListaUsuarios> listarUsuarios() {
        quien();
        Instant ahora = reloj.instant();
        Set<Long> conSesion = servicio.conSesionAbierta();
        List<FilaUsuario> filas = servicio.listar().stream().map(u -> {
            FilaUsuario f = new FilaUsuario(u.id(), u.nombre(), u.usuario(), u.correo(), u.activo(), u.bloqueado(ahora), conSesion.contains(u.id()),
                    u.claveInicial(), new ArrayList<>(u.asignados()));
            f.setDocumento(u.documento());
            f.setTelefono(u.telefono());
            f.setUltimoIngreso(u.ultimoIngreso() == null ? null : FECHA_HORA.format(u.ultimoIngreso().atZone(Fechas.BOGOTA)));
            f.setAlcanceFuerza(u.alcanceFuerza());
            f.setAlcancePunto(u.alcancePunto());
            f.setAlcanceRegional(u.alcanceRegional());
            return f;
        }).toList();
        List<PerfilAsignable> perfiles = servicio.perfilesDisponibles().stream().map(m -> {
            PerfilAsignable p = new PerfilAsignable(((Number) m.get("id")).intValue(), (String) m.get("nombre"));
            p.setDescripcion((String) m.get("descripcion"));
            p.setBaseId(m.get("baseId") == null ? null : ((Number) m.get("baseId")).intValue());
            return p;
        }).toList();
        ServicioUsuarios.OpcionesAlcance alcance = servicio.opcionesAlcance();
        List<PuntoEsm> puntos = alcance.puntos().stream().map(m -> {
            PuntoEsm p = new PuntoEsm(((Number) m.get("id")).longValue(), (String) m.get("nombre"));
            p.setSigla((String) m.get("sigla"));
            p.setFuerza((String) m.get("fuerza"));
            return p;
        }).toList();
        List<Regional> regionales = alcance.regionales().stream()
                .map(m -> new Regional(((Number) m.get("id")).longValue(), (String) m.get("nombre"))).toList();
        return ResponseEntity.ok(new ListaUsuarios(filas, perfiles, puntos, regionales, FUERZAS));
    }

    @Override
    public ResponseEntity<UsuarioGuardado> crearUsuario(DatosUsuario d) {
        Permisos.Quien q = quien(); // primero el permiso: sin la pantalla, 403 y no las reglas de validación
        ServicioUsuarios.Guardado g = servicio.guardar(datos(null, d, d == null ? null : d.getClaveInicial()), q);
        return ResponseEntity.status(HttpStatus.CREATED).body(guardado(g));
    }

    @Override
    public ResponseEntity<UsuarioGuardado> editarUsuario(Long id, DatosUsuario d) {
        // Editar no asigna contraseña: eso va por asignarClaveInicial
        Permisos.Quien q = quien();
        if (id == null || id <= 0) {
            throw ErrorNegocio.noEncontrado("El usuario no existe."); // un PUT nunca crea
        }
        return ResponseEntity.ok(guardado(servicio.guardar(datos(id, d, null), q)));
    }

    @Override
    public ResponseEntity<Mensaje> cambiarEstadoUsuario(Long id, EstadoActivo e) {
        Permisos.Quien q = quien();
        boolean activo = activo(e);
        servicio.cambiarEstado(id, activo, q);
        return ResponseEntity.ok(new Mensaje(activo ? "Usuario activado." : "Usuario inactivado."));
    }

    @Override
    public ResponseEntity<Mensaje> restablecerClave(Long id) {
        servicio.restablecer(id, quien());
        return ResponseEntity.ok(new Mensaje("Se envió al correo del usuario el enlace para definir una contraseña nueva."));
    }

    @Override
    public ResponseEntity<Mensaje> asignarClaveInicial(Long id, ClaveInicialNueva c) {
        Permisos.Quien q = quien();
        String clave = Validar.obligatorio(c == null ? null : c.getClave(), "clave", "Escriba la contraseña inicial.", 200);
        servicio.asignarClaveInicial(id, clave, q);
        return ResponseEntity.ok(new Mensaje("Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar."));
    }

    @Override
    public ResponseEntity<Mensaje> cerrarSesionUsuario(Long id) {
        boolean cerro = servicio.cerrarSesion(id, quien());
        return ResponseEntity.ok(new Mensaje(cerro ? "Sesión cerrada." : "El usuario no tenía una sesión abierta."));
    }

    @Override
    public ResponseEntity<Mensaje> desbloquearUsuario(Long id) {
        boolean estaba = servicio.desbloquear(id, quien());
        return ResponseEntity.ok(new Mensaje(estaba ? "Usuario desbloqueado." : "El usuario no estaba bloqueado."));
    }

    @Override
    public ResponseEntity<ParametrosSeguridad> parametrosSeguridad() {
        quien();
        return ResponseEntity.ok(new ParametrosSeguridad(parametros.minutosSesion(), Sesion.MINUTOS_MAXIMO));
    }

    @Override
    public ResponseEntity<Mensaje> cambiarParametrosSeguridad(CambioSeguridad c) {
        Permisos.Quien q = quien();
        Integer minutos = c == null ? null : c.getMinutosSesion();
        parametros.cambiarMinutosSesion(minutos, q);
        return ResponseEntity.ok(new Mensaje("Tiempo de inactividad actualizado: " + minutos + " minutos. Aplica desde la siguiente acción de cada usuario."));
    }

    @Override
    public ResponseEntity<CatalogoRoles> catalogoRoles() {
        quien();
        List<BaseRol> bases = roles.bases().stream().map(b -> new BaseRol(b.id(), b.nombre(),
                b.pantallas().stream().map(p -> new PantallaElegible(p.name(), p.titulo())).toList())).toList();
        List<RolAdicional> lista = roles.roles().stream().map(r -> {
            RolAdicional a = new RolAdicional(r.id(), r.nombre(), r.baseId(), r.activo(), r.pantallas(), r.usuarios());
            a.setDescripcion(r.descripcion());
            return a;
        }).toList();
        return ResponseEntity.ok(new CatalogoRoles(bases, lista));
    }

    @Override
    public ResponseEntity<RolCreado> crearRol(DatosRol d) {
        Permisos.Quien q = quien();
        int id = roles.crear(datosRol(d), q);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RolCreado(id, "Rol creado. Asígnelo en el formulario de cada usuario."));
    }

    @Override
    public ResponseEntity<Mensaje> editarRol(Integer id, DatosRol d) {
        Permisos.Quien q = quien();
        roles.editar(id, datosRol(d), q);
        return ResponseEntity.ok(new Mensaje("Rol actualizado. Aplica desde la siguiente acción de sus usuarios."));
    }

    @Override
    public ResponseEntity<Mensaje> cambiarEstadoRol(Integer id, EstadoActivo e) {
        Permisos.Quien q = quien();
        boolean activo = activo(e);
        long n = roles.cambiarEstado(id, activo, q);
        return ResponseEntity.ok(new Mensaje((activo ? "Rol activado" : "Rol inactivado") + " (" + n + " usuarios activos lo tienen)."));
    }

    @Override
    public ResponseEntity<Mensaje> renombrarPerfil(Integer id, NombrePerfil n) {
        servicio.renombrarPerfil(id, n == null ? null : n.getNombre(), n == null ? null : n.getDescripcion(), quien());
        return ResponseEntity.ok(new Mensaje("Perfil actualizado."));
    }

    private static ServicioUsuarios.Datos datos(Long id, DatosUsuario d, String claveInicial) {
        if (d == null) {
            throw ErrorNegocio.validacion("Faltan los datos del usuario.");
        }
        return new ServicioUsuarios.Datos(id, d.getNombre(), d.getDocumento(), d.getTelefono(), d.getUsuario(), d.getCorreo(), d.getPerfiles(),
                d.getAlcanceFuerza(), d.getAlcancePunto(), d.getAlcanceRegional(), claveInicial);
    }

    private static ServicioRoles.Datos datosRol(DatosRol d) {
        if (d == null) {
            throw ErrorNegocio.validacion("Faltan los datos del rol.");
        }
        return new ServicioRoles.Datos(d.getNombre(), d.getDescripcion(), d.getBaseId(), d.getPantallas());
    }

    private static boolean activo(EstadoActivo e) {
        if (e == null || e.getActivo() == null) {
            throw ErrorNegocio.validacion("Indique si queda activo.", "activo");
        }
        return e.getActivo();
    }

    private static UsuarioGuardado guardado(ServicioUsuarios.Guardado g) {
        return new UsuarioGuardado(g.id(), g.creado(), g.correoOk(), g.mensaje());
    }
}
