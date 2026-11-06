package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import co.com.agsamericas.digsa.seguridad.correo.CorreoSeguridad;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administración de usuarios (pantalla USUARIOS), portada de {@code identidad.ServicioUsuarios} del monolito con sus validaciones, textos y
 * reglas de autoprotección. Cambios respecto al monolito: el alcance ESM y OPL regional se valida contra la copia local de visitas (falla
 * cerrado si está vacía, D5) y las sesiones se cierran en la tabla {@code sesiones} con revocación ({@link ServicioSesiones}).
 */
@Service
public class ServicioUsuarios {

    private static final Pattern USUARIO = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final Set<String> FUERZAS_CON_SANIDAD = Set.of("EJC", "ARC", "FAC");
    /** Datos personales (Ley 1581): en la bitácora solo «modificado», sin valores (como el monolito). */
    private static final Set<String> PERSONALES = Set.of("nombre", "documento", "telefono", "correo");

    /** Datos del formulario de un usuario. */
    public record Datos(Long id, String nombre, String documento, String telefono, String usuario, String correo, List<Integer> perfiles,
                        String alcanceFuerza, Long alcancePunto, Long alcanceRegional, String claveInicial) {
    }

    public record Guardado(long id, boolean creado, boolean correoOk, String mensaje) {
    }

    private record Asignacion(Set<Integer> ids, Set<Perfil> efectivos) {
    }

    private final RepositorioUsuarios usuarios;
    private final RepositorioCopias copias;
    private final ServicioClaves claves;
    private final ServicioSesiones cierres;
    private final PasswordEncoder codificador;
    private final BitacoraSeguridad bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private final RepositorioSesiones sesiones;
    private final ParametrosSeguridad parametros;
    private final EventosUsuario eventos;

    public ServicioUsuarios(RepositorioUsuarios usuarios, RepositorioCopias copias, ServicioClaves claves, ServicioSesiones cierres,
                            PasswordEncoder codificador, BitacoraSeguridad bitacora, TransactionTemplate tx, Clock reloj,
                            RepositorioSesiones sesiones, ParametrosSeguridad parametros, EventosUsuario eventos) {
        this.eventos = eventos;
        this.sesiones = sesiones;
        this.parametros = parametros;
        this.usuarios = usuarios;
        this.copias = copias;
        this.claves = claves;
        this.cierres = cierres;
        this.codificador = codificador;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    public List<Usuario> listar() {
        return usuarios.todos();
    }

    public Guardado guardar(Datos d, Permisos.Quien quien) {
        boolean nuevo = d.id() == null || d.id() <= 0;
        String nombre = texto(d.nombre());
        String usuario = texto(d.usuario()).toLowerCase(Locale.ROOT);
        String correo = Correos.normalizar(texto(d.correo()));
        String documento = vacioANulo(d.documento());
        // Móvil en E.164 para los SMS de las alertas críticas; el que no cambió se conserva (usuarios anteriores a la validación)
        String telefono = telefonoMovil(vacioANulo(d.telefono()), nuevo ? null : usuarios.porId(d.id()).map(Usuario::telefono).orElse(null));
        if (nombre.codePointCount(0, nombre.length()) < 3 || nombre.length() > 150) {
            throw ErrorNegocio.validacion("Escriba el nombre completo (mínimo 3 caracteres).");
        }
        if (!USUARIO.matcher(usuario).matches()) {
            throw ErrorNegocio.validacion("El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.");
        }
        if (!Correos.valido(correo)) {
            throw ErrorNegocio.validacion("Escriba un correo válido.");
        }
        if (documento != null && documento.length() > 30 || telefono != null && telefono.length() > 30) {
            throw ErrorNegocio.validacion("El documento y el teléfono tienen máximo 30 caracteres.");
        }
        Asignacion asignacion = asignacion(d.perfiles());
        Set<Perfil> perfiles = asignacion.efectivos(); // directos más la base de cada rol adicional: datos de alcance y combinaciones
        Alcance.combinacionInvalida(perfiles).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        if (perfiles.contains(Perfil.AUDITOR) && documento == null) {
            throw ErrorNegocio.validacion("El documento es obligatorio para los auditores: va en la notificación previa de la visita (decisión 26).");
        }
        String fuerza = perfiles.contains(Perfil.SANIDAD) ? texto(d.alcanceFuerza()).toUpperCase(Locale.ROOT) : null;
        if (fuerza != null && !FUERZAS_CON_SANIDAD.contains(fuerza)) {
            throw ErrorNegocio.validacion("El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).");
        }
        Long punto = perfiles.contains(Perfil.ESM) ? d.alcancePunto() : null;
        if (perfiles.contains(Perfil.ESM) && !copias.esmActivo(punto)) {
            throw ErrorNegocio.validacion("El perfil ESM necesita el establecimiento (ESM activo).");
        }
        Long regional = perfiles.contains(Perfil.OPL_REGIONAL) ? d.alcanceRegional() : null;
        if (perfiles.contains(Perfil.OPL_REGIONAL) && !copias.regionalActiva(regional)) {
            throw ErrorNegocio.validacion("El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato).");
        }
        // La contraseña se toma tal cual (sin recortar), igual que en asignarClaveInicial; en blanco = sin contraseña inicial (va el correo)
        String claveInicial = nuevo && d.claveInicial() != null && !d.claveInicial().isBlank() ? d.claveInicial() : null;
        if (claveInicial != null) {
            Claves.problema(claveInicial).ifPresent(p -> {
                throw ErrorNegocio.validacion("Contraseña inicial: " + p);
            });
        }
        long excepto = nuevo ? 0 : d.id();
        if (usuarios.existeUsuario(usuario, excepto)) {
            throw ErrorNegocio.validacion("Ya existe un usuario con ese nombre de usuario.");
        }
        if (usuarios.existeCorreo(correo, excepto)) {
            throw ErrorNegocio.validacion("Ya existe un usuario con ese correo.");
        }
        Instant ahora = reloj.instant();
        try {
            if (nuevo) {
                // BCrypt fuera de la transacción; el usuario y sus perfiles se confirman antes de que salga el correo
                String claveAleatoria = codificador.encode(Huellas.secreto(24));
                String inicialCodificada = claveInicial == null ? null : codificador.encode(claveInicial);
                long id = tx.execute(t -> {
                    eventos.bloquearRoles(asignacion.ids()); // orden global: roles → usuario
                    long nuevoId = usuarios.crear(nombre, documento, telefono, usuario, correo, claveAleatoria, fuerza, punto, regional, ahora);
                    usuarios.reemplazarAsignados(nuevoId, asignacion.ids());
                    if (inicialCodificada != null) {
                        usuarios.asignarClaveInicial(nuevoId, inicialCodificada, ahora);
                    }
                    eventos.publicar("usuario.creado", nuevoId);
                    return nuevoId;
                });
                if (inicialCodificada != null) {
                    bitacora.escribir(Bitacora.Operacion.INSERCION, "usuario_creado", "Usuario " + usuario
                            + " creado con contraseña inicial; perfiles: " + ids(asignacion.ids()), quien.id(), quien.usuario(), null);
                    return new Guardado(id, true, true, "Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: "
                            + "deberá cambiarla en su primer ingreso.");
                }
                Usuario creado = usuarios.porId(id).orElseThrow();
                CorreoSeguridad.Resultado e = claves.enviarEnlace(creado, "Bienvenido a la Suite DIGSA",
                        "Se creó su usuario «" + usuario + "» en la Suite DIGSA. Defina su contraseña con el siguiente enlace.", false);
                bitacora.escribir(Bitacora.Operacion.INSERCION, "usuario_creado", "Usuario " + usuario + " creado; perfiles: "
                        + ids(asignacion.ids()) + (e.ok() ? "" : " (el correo de bienvenida NO salió)"), quien.id(), quien.usuario(), null);
                String msg = e.ok() ? "Usuario creado. Se le envió el enlace para definir su contraseña."
                        : "Usuario creado, pero NO se pudo enviar el correo (" + e.error() + "). Use «Restablecer contraseña» cuando el correo funcione.";
                return new Guardado(id, true, e.ok(), msg);
            }
            long editado = d.id();
            if (editado == quien.id() && !asignacion.ids().contains(Perfil.ADMINISTRADOR.id())) {
                throw ErrorNegocio.validacion("No puede quitarse a sí mismo el perfil Administrador.");
            }
            Usuario antes = tx.execute(t -> {
                // Dos administradores editando al mismo usuario: uno espera al otro y el segundo compara contra lo que dejó el primero.
                // Orden de bloqueo fijo (administradores activos → usuario) para no cruzarse con otra transacción
                eventos.bloquearRoles(asignacion.ids()); // orden global: roles → administradores activos → usuario
                boolean quitaAdministrador = !asignacion.ids().contains(Perfil.ADMINISTRADOR.id());
                if (quitaAdministrador) {
                    usuarios.bloquearAdministradoresActivos();
                }
                usuarios.bloquear(editado);
                Usuario actual = usuarios.porId(editado).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
                if (quitaAdministrador && actual.activo() && actual.perfiles().contains(Perfil.ADMINISTRADOR)) {
                    exigirOtroAdministrador(editado);
                }
                usuarios.actualizar(editado, nombre, documento, telefono, usuario, correo, fuerza, punto, regional, ahora);
                usuarios.reemplazarAsignados(editado, asignacion.ids());
                if (!correo.equals(actual.correo())) {
                    usuarios.anularEnlace(editado); // el enlace enviado al correo anterior deja de servir
                }
                // Perfiles, roles o alcance: permisos_cambiados (el gateway fuerza el refresco del token); lo demás, actualizado
                boolean permisos = !actual.asignados().equals(asignacion.ids()) || !Objects.equals(actual.alcanceFuerza(), fuerza)
                        || !Objects.equals(actual.alcancePunto(), punto) || !Objects.equals(actual.alcanceRegional(), regional);
                eventos.publicar(permisos ? "usuario.permisos_cambiados" : "usuario.actualizado", editado);
                return actual;
            });
            bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "usuario_editado", "Usuario " + usuario + " editado",
                    foto(antes.nombre(), antes.documento(), antes.telefono(), antes.usuario(), antes.correo(), antes.asignados(),
                            antes.alcanceFuerza(), antes.alcancePunto(), antes.alcanceRegional()),
                    foto(nombre, documento, telefono, usuario, correo, asignacion.ids(), fuerza, punto, regional), PERSONALES, quien.id(),
                    quien.usuario());
            return new Guardado(editado, false, true, "Usuario actualizado.");
        } catch (DuplicateKeyException e) {
            // Dos administradores a la vez (prueba 2: «de forma simultánea en dos equipos»): la base decide quién gana
            throw ErrorNegocio.validacion("Ya existe un usuario con ese nombre de usuario o ese correo.");
        }
    }

    /** Activa o inactiva. Inactivar anula el enlace pendiente y cierra la sesión (decisión 7.3). */
    public void cambiarEstado(long id, boolean activo, Permisos.Quien quien) {
        if (id == quien.id() && !activo) {
            throw ErrorNegocio.validacion("No puede inactivar su propio usuario.");
        }
        Instant ahora = reloj.instant();
        // Una sola transacción: un usuario inactivo nunca queda con su enlace vivo ni con la sesión (y su refresh token) abierta
        record Cambio(Usuario antes, boolean cerro) {
        }
        Cambio c = tx.execute(t -> {
            if (!activo) {
                usuarios.bloquearAdministradoresActivos(); // mismo orden de bloqueo que la edición: administradores activos → usuario
            }
            usuarios.bloquear(id);
            Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
            if (!activo && u.activo() && u.perfiles().contains(Perfil.ADMINISTRADOR)) {
                exigirOtroAdministrador(id);
            }
            usuarios.cambiarEstado(id, activo, ahora);
            boolean cerrada = false;
            if (!activo) {
                usuarios.anularEnlace(id);
                cerrada = cierres.cerrarDeUsuario(id, Motivo.INACTIVO);
            }
            if (u.activo() != activo) {
                eventos.publicar("usuario.estado_cambiado", id);
            }
            return new Cambio(u, cerrada);
        });
        Usuario u = c.antes();
        boolean cerro = c.cerro();
        bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "usuario_estado", "Usuario " + u.usuario() + (activo ? " activado" : " inactivado"),
                Map.of("activo", u.activo()), Map.of("activo", activo), Set.of(), quien.id(), quien.usuario());
        if (cerro) {
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por inactivación del usuario",
                    quien.id(), quien.usuario(), null);
        }
    }

    /** Quita el bloqueo por intentos fallidos antes de los 15 minutos. @return si estaba bloqueado */
    public boolean desbloquear(long id, Permisos.Quien quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        Instant ahora = reloj.instant();
        boolean estaba = Boolean.TRUE.equals(tx.execute(t -> {
            boolean b = usuarios.desbloquear(id, ahora);
            if (b) {
                eventos.publicar("usuario.actualizado", id);
            }
            return b;
        }));
        if (estaba) {
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "usuario_desbloqueado", "Usuario " + u.usuario() + " desbloqueado por el administrador",
                    quien.id(), quien.usuario(), null);
        }
        return estaba;
    }

    /** Decisión 7.1: el administrador cierra la sesión abierta de un usuario. @return si tenía una */
    public boolean cerrarSesion(long id, Permisos.Quien quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        boolean cerro = cierres.cerrarDeUsuario(id, Motivo.CERRADA);
        if (cerro) {
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por el administrador",
                    quien.id(), quien.usuario(), null);
        }
        return cerro;
    }

    /** Contraseña inicial nueva asignada por el administrador (sin correo); cierra la sesión abierta del usuario. */
    public void asignarClaveInicial(long id, String clave, Permisos.Quien quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        Claves.problema(clave == null ? "" : clave).ifPresent(p -> {
            throw ErrorNegocio.validacion("Contraseña inicial: " + p);
        });
        String codificada = codificador.encode(clave);
        Instant ahora = reloj.instant();
        boolean cerro = Boolean.TRUE.equals(tx.execute(t -> {
            usuarios.asignarClaveInicial(id, codificada, ahora);
            return cierres.cerrarDeUsuario(id, Motivo.CLAVE);
        }));
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "usuario_restablecer", "Contraseña inicial asignada a " + u.usuario(), quien.id(),
                quien.usuario(), null);
        if (cerro) {
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por asignación de contraseña",
                    quien.id(), quien.usuario(), null);
        }
    }

    /** Restablecer: enlace al correo con cambio obligatorio. Si el correo no sale, 502. */
    public void restablecer(long id, Permisos.Quien quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        CorreoSeguridad.Resultado e = claves.enviarEnlace(u, "Defina una contraseña nueva",
                "El administrador de la Suite DIGSA pidió que defina una contraseña nueva.", true);
        if (!e.ok()) {
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + e.error());
        }
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "usuario_restablecer", "Restablecer contraseña de " + u.usuario(), quien.id(),
                quien.usuario(), null);
    }

    /** Nombres configurables de los perfiles base. */
    public void renombrarPerfil(int id, String nombre, String descripcion, Permisos.Quien quien) {
        String n = texto(nombre);
        String d = vacioANulo(descripcion);
        if (n.length() < 3 || n.length() > 60) {
            throw ErrorNegocio.validacion("El nombre del perfil debe tener de 3 a 60 caracteres.");
        }
        if (d != null && d.length() > 200) {
            throw ErrorNegocio.validacion("La descripción tiene máximo 200 caracteres.");
        }
        Perfil.deId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El perfil no existe."));
        String antes;
        try {
            antes = tx.execute(t -> {
                String previo = usuarios.nombrePerfilBloqueado(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El perfil no existe."));
                if (usuarios.nombresPerfiles().entrySet().stream().anyMatch(e -> e.getKey() != id && e.getValue().equalsIgnoreCase(n))) {
                    throw ErrorNegocio.validacion("Ya existe otro perfil con ese nombre.");
                }
                usuarios.renombrarPerfil(id, n, d);
                return previo;
            });
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe otro perfil con ese nombre.");
        }
        bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "perfil_editado", "Perfil " + id, Map.of("nombre", antes), Map.of("nombre", n),
                Set.of(), quien.id(), quien.usuario());
    }

    /**
     * Debe quedar al menos un Administrador activo además de {@code excepto}. Se llama dentro de la transacción del cambio, después de
     * {@link RepositorioUsuarios#bloquearAdministradoresActivos()}: cuenta con una lectura nueva, así que dos administradores que se
     * inactivan o se quitan el perfil a la vez se serializan y el segundo ve lo que dejó el primero.
     */
    private void exigirOtroAdministrador(long excepto) {
        if (usuarios.contarAdministradoresActivos(excepto) == 0) {
            throw ErrorNegocio.validacion("Debe quedar al menos un usuario Administrador activo.");
        }
    }

    public List<Map<String, Object>> perfilesDisponibles() {
        return usuarios.perfilesActivos();
    }

    /** Opciones de alcance del formulario (como {@code opcionesAlcance} del monolito), desde la copia local de visitas. */
    public record OpcionesAlcance(List<Map<String, Object>> puntos, List<Map<String, Object>> regionales) {
    }

    public OpcionesAlcance opcionesAlcance() {
        return new OpcionesAlcance(copias.esmActivos(), copias.regionalesActivas());
    }

    /** Usuarios con una sesión que bloquea un ingreso nuevo (decisión 7.1), para la columna «sesión abierta» del listado. */
    public Set<Long> conSesionAbierta() {
        return sesiones.conSesionQueBloquea(reloj.instant(), parametros.minutosSesion());
    }

    /** Foto de los campos editables para la bitácora de antes y después (como el monolito). */
    private static Map<String, Object> foto(String nombre, String documento, String telefono, String usuario, String correo,
                                            Set<Integer> perfiles, String fuerza, Long punto, Long regional) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", nombre);
        m.put("documento", documento);
        m.put("telefono", telefono);
        m.put("usuario", usuario);
        m.put("correo", correo);
        m.put("perfiles", perfiles.stream().map(String::valueOf).toList());
        m.put("fuerza", fuerza);
        m.put("punto", punto);
        m.put("regional", regional);
        return m;
    }

    /**
     * Teléfono móvil colombiano en E.164 (+57 y 10 dígitos que empiezan por 3) para los SMS de las alertas críticas. Vacío se admite; un
     * valor igual al guardado se conserva para no impedir editar a los usuarios registrados antes de la validación.
     */
    static String telefonoMovil(String escrito, String actual) {
        if (escrito == null || escrito.equals(actual)) {
            return escrito;
        }
        return Alertas.normalizarTelefono(escrito).orElseThrow(() -> ErrorNegocio.validacion(
                "El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de "
                        + "las alertas críticas.", "telefono"));
    }

    /** Perfiles y roles marcados: los ids que se guardan y los perfiles de alcance con que se validan los datos y las combinaciones. */
    private Asignacion asignacion(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ErrorNegocio.validacion("Marque al menos un perfil.");
        }
        Map<Integer, Integer> activos = usuarios.activosConBase();
        Map<Integer, Roles.Adicional> roles = usuarios.rolesActivos();
        Set<Perfil> directos = EnumSet.noneOf(Perfil.class);
        List<Roles.Adicional> adicionales = new ArrayList<>();
        Set<Integer> marcados = new TreeSet<>();
        for (Integer id : ids) {
            if (id == null || !activos.containsKey(id)) {
                throw ErrorNegocio.validacion("Hay un perfil no válido.");
            }
            marcados.add(id);
            if (activos.get(id) == null) {
                directos.add(Perfil.deId(id).orElseThrow(() -> ErrorNegocio.validacion("Hay un perfil no válido.")));
            } else {
                adicionales.add(Optional.ofNullable(roles.get(id)).orElseThrow(() -> ErrorNegocio.validacion("Hay un perfil no válido.")));
            }
        }
        Roles.asignacionInvalida(directos, adicionales).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        return new Asignacion(Set.copyOf(marcados), Roles.deAlcance(directos, adicionales));
    }

    private static String ids(Set<Integer> ids) {
        return String.join(",", new TreeSet<>(ids).stream().map(String::valueOf).toList());
    }

    private static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    private static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
