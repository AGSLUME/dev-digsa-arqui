// /api/usuarios/** del contrato seguridad 0.4.0 (pantalla USUARIOS, solo Administrador), con las reglas y los textos del monolito:
// identidad/ServicioUsuarios, identidad/ServicioRoles, reglas/Roles, reglas/Alcance y reglas/Alertas.normalizarTelefono.
import { BASES, elegibles, FUERZAS, PANTALLAS, permitidas, PUNTOS, REGIONALES } from './datos.mjs';

const USUARIO = /^[a-z0-9._-]{3,50}$/;
const CORREO = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const TELEFONO =
  'El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de las alertas críticas.';
const CON_ALCANCE = ['DIGSA', 'Sanidad', 'ESM', 'OPL nacional', 'OPL regional', 'Analista'];
const INTERVENTORIA = ['Auditor', 'Coordinador', 'Administrador', 'Analista'];
const MAXIMO_MINUTOS = 480;

/** Error de negocio: 422 por omisión, con `campo` cuando el monolito lo marca. */
class Negocio extends Error {
  constructor(mensaje, { estado = 422, campo } = {}) {
    super(mensaje);
    this.estado = estado;
    this.campo = campo;
  }
}

const texto = (v) => (typeof v === 'string' ? v.trim() : '');
const vacioANulo = (v) => (texto(v) === '' ? null : texto(v));
const codepoints = (s) => [...s].length;

/** reglas/Alertas.normalizarTelefono: E.164 de un móvil colombiano, o null. */
export function normalizarTelefono(t) {
  let s = String(t).trim().replace(/[\s().-]+/g, '');
  if (s.startsWith('+57')) s = s.slice(3);
  else if (s.startsWith('0057')) s = s.slice(4);
  else if (s.startsWith('57') && s.length === 12) s = s.slice(2);
  return /^3\d{9}$/.test(s) ? '+57' + s : null;
}

/** Hora de Bogotá (UTC−5, sin horario de verano) como `yyyy-MM-dd HH:mm`. */
export const bogota = (ms) => new Date(ms - 5 * 3_600_000).toISOString().slice(0, 16).replace('T', ' ');

/**
 * @param {object} c ayudantes del servidor: `estado()` (el estado vigente), `filtroSesion`, `sesionAbierta`, `bloqueado`, `cerrar`,
 *   `enviarEnlace`, `errorPolitica`, `usuarioDe` y `bitacora` (opcional: `registrar` y `registrarCambios`, mock/logs.mjs).
 */
export function crearRutasUsuarios(c) {
  const e = () => c.estado();
  const nada = () => undefined;
  const log = c.bitacora ?? { registrar: nada, registrarCambios: nada };
  /** Datos personales (Ley 1581): en la bitácora solo consta que cambiaron (ServicioUsuarios.PERSONALES). */
  const PERSONALES = ['nombre', 'documento', 'telefono', 'correo'];
  const foto = (u) => ({ nombre: u.nombre, documento: u.documento, telefono: u.telefono, usuario: u.usuario, correo: u.correo, perfiles: u.perfiles, fuerza: u.alcanceFuerza, punto: u.alcancePunto, regional: u.alcanceRegional });
  const perfilDe = (id) => e().perfiles.find((p) => p.id === id) ?? e().roles.find((r) => r.id === id);
  const nombresPerfiles = () => [...e().perfiles.filter((p) => p.activo), ...e().roles].map((p) => [p.id, p.nombre]);

  /** El filtro de sesión más la pantalla USUARIOS (@permisos.pantalla('USUARIOS')): 403 a quien no es Administrador. */
  function administrador(req, r) {
    const f = c.filtroSesion(req, r, true);
    if (!f) return null;
    if (!permitidas(f.u.perfiles, e().roles).some((p) => p.ruta === 'usuarios')) {
      r.problema(403, 'No tiene permiso para esta acción.');
      return null;
    }
    return f;
  }

  function usuarioPorId(id) {
    const u = c.usuarioDe(id);
    if (!u) throw new Negocio('El usuario no existe.', { estado: 404 });
    return u;
  }

  function rolPorId(id) {
    const rol = e().roles.find((x) => x.id === id);
    if (!rol) throw new Negocio('El rol no existe.', { estado: 404 });
    return rol;
  }

  /** ServicioUsuarios.asignacion + Roles.asignacionInvalida + Alcance.combinacionInvalida: los perfiles de alcance. */
  function asignacion(ids) {
    if (!Array.isArray(ids) || ids.length === 0) throw new Negocio('Marque al menos un perfil.');
    const directos = [];
    const adicionales = [];
    for (const id of ids) {
      const base = e().perfiles.find((p) => p.id === id && p.activo);
      const rol = e().roles.find((x) => x.id === id && x.activo);
      if (base) directos.push(base);
      else if (rol) adicionales.push(rol);
      else throw new Negocio('Hay un perfil no válido.');
    }
    if (adicionales.length && directos.length) {
      throw new Negocio('Un usuario con un rol adicional no tiene además perfiles: el rol ya trae el alcance de su perfil base.');
    }
    if (new Set(adicionales.map((x) => x.baseId)).size > 1) throw new Negocio('Los roles adicionales de un usuario deben tener el mismo perfil base.');
    const efectivos = [...new Set([...directos.map((p) => p.codigo), ...adicionales.map((x) => e().perfiles.find((p) => p.id === x.baseId).codigo)])];
    const opl = efectivos.some((p) => p.startsWith('OPL'));
    if (opl && efectivos.some((p) => INTERVENTORIA.includes(p))) throw new Negocio('Los perfiles del OPL (entidad auditada) no se combinan con los de la interventoría.');
    const conAlcance = efectivos.filter((p) => CON_ALCANCE.includes(p));
    if (conAlcance.length > 1) throw new Negocio(`Un usuario tiene un solo perfil de consulta u OPL (${conAlcance.join(', ')}): cada uno ve un alcance distinto.`);
    return { ids: [...new Set(ids)].sort((a, b) => a - b), efectivos };
  }

  /** ServicioUsuarios.guardar, en su orden de validación. */
  async function guardar(d, quien, id) {
    const nuevo = id === null;
    const actual = nuevo ? null : usuarioPorId(id);
    const nombre = texto(d.nombre);
    const usuario = texto(d.usuario).toLowerCase();
    const correo = texto(d.correo).toLowerCase();
    const documento = vacioANulo(d.documento);
    let telefono = vacioANulo(d.telefono);
    if (telefono !== null && telefono !== actual?.telefono) {
      telefono = normalizarTelefono(telefono);
      if (!telefono) throw new Negocio(TELEFONO, { campo: 'telefono' });
    }
    if (codepoints(nombre) < 3 || nombre.length > 150) throw new Negocio('Escriba el nombre completo (mínimo 3 caracteres).');
    if (!USUARIO.test(usuario)) {
      throw new Negocio('El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.');
    }
    if (!CORREO.test(correo) || correo.length > 150) throw new Negocio('Escriba un correo válido.');
    if ((documento && documento.length > 30) || (telefono && telefono.length > 30)) throw new Negocio('El documento y el teléfono tienen máximo 30 caracteres.');
    const a = asignacion(d.perfiles);
    if (a.efectivos.includes('Auditor') && documento === null) {
      throw new Negocio('El documento es obligatorio para los auditores: va en la notificación previa de la visita (decisión 26).');
    }
    const fuerza = a.efectivos.includes('Sanidad') ? texto(d.alcanceFuerza).toUpperCase() : null;
    if (fuerza !== null && !FUERZAS.some((f) => f.codigo === fuerza)) throw new Negocio('El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).');
    const punto = a.efectivos.includes('ESM') ? d.alcancePunto : null;
    if (a.efectivos.includes('ESM') && !PUNTOS.some((p) => p.id === punto)) throw new Negocio('El perfil ESM necesita el establecimiento (ESM activo).');
    const regional = a.efectivos.includes('OPL regional') ? d.alcanceRegional : null;
    if (a.efectivos.includes('OPL regional') && !REGIONALES.some((x) => x.id === regional)) {
      throw new Negocio('El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato).');
    }
    const claveInicial = nuevo ? vacioANulo(d.claveInicial) : null;
    if (claveInicial !== null) {
      const p = c.errorPolitica(claveInicial);
      if (p) throw new Negocio('Contraseña inicial: ' + p);
    }
    const otros = e().usuarios.filter((x) => x.id !== id);
    if (otros.some((x) => x.usuario === usuario)) throw new Negocio('Ya existe un usuario con ese nombre de usuario.');
    if (otros.some((x) => x.correo === correo)) throw new Negocio('Ya existe un usuario con ese correo.');
    const datos = { nombre, usuario, correo, documento, telefono, perfiles: a.ids, alcanceFuerza: fuerza, alcancePunto: punto, alcanceRegional: regional };
    if (!nuevo) {
      if (actual.id === quien.id && !a.efectivos.includes('Administrador')) throw new Negocio('No puede quitarse a sí mismo el perfil Administrador.');
      if (correo !== actual.correo) for (const [k, x] of e().enlaces) if (x.usuarioId === actual.id) e().enlaces.delete(k);
      const antes = foto(actual);
      Object.assign(actual, datos);
      log.registrarCambios('usuario_editado', `Usuario ${usuario} editado`, antes, foto(actual), PERSONALES, { quien });
      return { estado: 200, cuerpo: { id: actual.id, creado: false, correoOk: true, mensaje: 'Usuario actualizado.', enlaceLocal: null } };
    }
    const u = {
      ...datos,
      id: Math.max(...e().usuarios.map((x) => x.id)) + 1,
      clave: claveInicial,
      claveInicial: claveInicial !== null,
      definir: claveInicial === null,
      activo: true,
      intentos: 0,
      bloqueadoHasta: 0,
      ultimoIngreso: null,
    };
    e().usuarios.push(u);
    const perfilesLog = a.ids.join(', ');
    if (claveInicial !== null) {
      log.registrar('usuario_creado', `Usuario ${usuario} creado con contraseña inicial; perfiles: ${perfilesLog}`, { quien });
      const mensaje = 'Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: deberá cambiarla en su primer ingreso.';
      return { estado: 201, cuerpo: { id: u.id, creado: true, correoOk: true, mensaje, enlaceLocal: null } };
    }
    log.registrar('usuario_creado', `Usuario ${usuario} creado; perfiles: ${perfilesLog}`, { quien });
    try {
      await c.enviarEnlace(u, 'Bienvenido a la Suite DIGSA', `Se creó su usuario «${usuario}» en la Suite DIGSA. Defina su contraseña con el siguiente enlace.`);
      return { estado: 201, cuerpo: { id: u.id, creado: true, correoOk: true, mensaje: 'Usuario creado. Se le envió el enlace para definir su contraseña.', enlaceLocal: null } };
    } catch (x) {
      const mensaje = `Usuario creado, pero NO se pudo enviar el correo (${x.message}). Use «Restablecer contraseña» cuando el correo funcione.`;
      return { estado: 201, cuerpo: { id: u.id, creado: true, correoOk: false, mensaje, enlaceLocal: null } };
    }
  }

  function fila(u) {
    return {
      id: u.id,
      nombre: u.nombre,
      documento: u.documento,
      telefono: u.telefono,
      usuario: u.usuario,
      correo: u.correo,
      activo: u.activo,
      ultimoIngreso: u.ultimoIngreso,
      bloqueado: c.bloqueado(u),
      sesionAbierta: c.sesionAbierta(u),
      claveInicial: u.claveInicial,
      perfiles: u.perfiles,
      alcanceFuerza: u.alcanceFuerza,
      alcancePunto: u.alcancePunto,
      alcanceRegional: u.alcanceRegional,
    };
  }

  const usuariosActivosCon = (rolId) => e().usuarios.filter((u) => u.activo && u.perfiles.includes(rolId)).length;

  /** ServicioRoles.nombre / descripcion / pantallas. */
  function datosRol(d, excepto, base) {
    const nombre = texto(d.nombre);
    if (nombre.length < 3 || nombre.length > 60) throw new Negocio('El nombre del rol debe tener de 3 a 60 caracteres.');
    if (nombresPerfiles().some(([id, n]) => id !== excepto && n.toLowerCase() === nombre.toLowerCase())) throw new Negocio('Ya existe un perfil o rol con ese nombre.');
    const descripcion = texto(d.descripcion);
    if (descripcion.length > 200) throw new Negocio('La descripción tiene máximo 200 caracteres.');
    const codigos = Array.isArray(d.pantallas) ? d.pantallas : [];
    if (codigos.some((x) => !PANTALLAS.some((p) => p.codigo === x))) throw new Negocio('Hay una pantalla no válida.');
    if (!BASES.includes(base)) throw new Negocio('El perfil base no es válido: un rol adicional no deriva del Administrador ni del Auditor.');
    if (codigos.length === 0) throw new Negocio('Marque al menos una pantalla para el rol.');
    const validas = elegibles(base);
    for (const x of codigos) {
      if (!validas.some((p) => p.codigo === x)) {
        const p = PANTALLAS.find((y) => y.codigo === x);
        throw new Negocio(`La pantalla «${p.titulo}» no es del perfil base ${e().perfiles.find((y) => y.id === base).codigo}.`);
      }
    }
    return { nombre, descripcion: descripcion || null, pantallas: [...new Set(codigos)].sort() };
  }

  const activo = (b) => {
    if (typeof b.activo !== 'boolean') throw new Negocio('La petición no tiene el formato esperado.', { estado: 400 });
    return b.activo;
  };

  // [método, patrón, manejador(f, cuerpo, id)] → { estado, cuerpo } o solo el cuerpo (200)
  const rutas = [
    [
      'GET',
      /^\/api\/usuarios$/,
      () => ({
        usuarios: [...e().usuarios].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')).map(fila),
        // perfilesActivos(): base_id NULLS FIRST, id
        perfiles: [...e().perfiles.filter((p) => p.activo), ...e().roles.filter((r) => r.activo).sort((a, b) => a.id - b.id)].map((p) => ({
          id: p.id,
          nombre: p.nombre,
          descripcion: p.descripcion,
          baseId: p.baseId ?? null,
        })),
        puntos: [...PUNTOS].sort((a, b) => a.fuerza.localeCompare(b.fuerza) || a.nombre.localeCompare(b.nombre, 'es')),
        regionales: REGIONALES,
        fuerzas: FUERZAS,
      }),
    ],
    ['POST', /^\/api\/usuarios$/, (f, b) => guardar(b, f.u, null)],
    ['PUT', /^\/api\/usuarios\/(\d+)$/, (f, b, id) => guardar(b, f.u, id)],
    [
      'PUT',
      /^\/api\/usuarios\/(\d+)\/estado$/,
      (f, b, id) => {
        const quiere = activo(b);
        if (id === f.u.id && !quiere) throw new Negocio('No puede inactivar su propio usuario.');
        const u = usuarioPorId(id);
        log.registrarCambios('usuario_estado', `Usuario ${u.usuario} ${quiere ? 'activado' : 'inactivado'}`, { activo: u.activo }, { activo: quiere }, [], { quien: f.u });
        u.activo = quiere;
        if (!quiere) {
          for (const [k, x] of e().enlaces) if (x.usuarioId === id) e().enlaces.delete(k);
          const s = e().sesiones.get(e().huellas.get(id));
          if (s) c.cerrar(s, 'inactivo');
        }
        return { mensaje: quiere ? 'Usuario activado.' : 'Usuario inactivado.' };
      },
    ],
    [
      'POST',
      /^\/api\/usuarios\/(\d+)\/restablecer$/,
      async (f, _b, id) => {
        const u = usuarioPorId(id);
        try {
          await c.enviarEnlace(u, 'Defina una contraseña nueva', 'El administrador de la Suite DIGSA pidió que defina una contraseña nueva.');
        } catch (x) {
          throw new Negocio('No se pudo enviar el correo: ' + x.message, { estado: 502 });
        }
        u.definir = true; // cambio obligatorio: con la clave anterior el ingreso pide definirla
        log.registrar('usuario_restablecer', `Restablecer contraseña de ${u.usuario}`, { quien: f.u });
        return { mensaje: 'Se envió al correo del usuario el enlace para definir una contraseña nueva.' };
      },
    ],
    [
      'POST',
      /^\/api\/usuarios\/(\d+)\/clave-inicial$/,
      (f, b, id) => {
        const u = usuarioPorId(id);
        const p = c.errorPolitica(typeof b.clave === 'string' ? b.clave : '');
        if (p) throw new Negocio('Contraseña inicial: ' + p);
        Object.assign(u, { clave: b.clave, claveInicial: true, definir: false });
        log.registrar('usuario_restablecer', `Contraseña inicial asignada a ${u.usuario}`, { quien: f.u });
        const s = e().sesiones.get(e().huellas.get(id));
        if (s) c.cerrar(s, 'cerrada');
        return { mensaje: 'Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar.' };
      },
    ],
    [
      'POST',
      /^\/api\/usuarios\/(\d+)\/cerrar-sesion$/,
      (f, _b, id) => {
        const u = usuarioPorId(id);
        const abierta = c.sesionAbierta(u);
        if (abierta) {
          c.cerrar(e().sesiones.get(e().huellas.get(id)), 'cerrada');
          log.registrar('sesion_cerrada', `Sesión de ${u.usuario} cerrada por el administrador`, { quien: f.u });
        }
        return { mensaje: abierta ? 'Sesión cerrada.' : 'El usuario no tenía una sesión abierta.' };
      },
    ],
    [
      'POST',
      /^\/api\/usuarios\/(\d+)\/desbloquear$/,
      (f, _b, id) => {
        const u = usuarioPorId(id);
        const estaba = c.bloqueado(u);
        if (estaba) log.registrar('usuario_desbloqueado', `Usuario ${u.usuario} desbloqueado por el administrador`, { quien: f.u });
        Object.assign(u, { bloqueadoHasta: 0, intentos: 0 });
        return { mensaje: estaba ? 'Usuario desbloqueado.' : 'El usuario no estaba bloqueado.' };
      },
    ],
    ['GET', /^\/api\/usuarios\/seguridad$/, () => ({ minutosSesion: e().minutos, minutosMaximo: MAXIMO_MINUTOS })],
    [
      'PUT',
      /^\/api\/usuarios\/seguridad$/,
      (f, b) => {
        const m = b.minutosSesion;
        if (m === null || m === undefined) throw new Negocio('Escriba los minutos de inactividad.', { campo: 'minutosSesion' });
        if (!Number.isInteger(m) || m < 1 || m > MAXIMO_MINUTOS) {
          throw new Negocio('Los minutos de inactividad deben ser un número entero de 1 a 480.', { campo: 'minutosSesion' });
        }
        log.registrarCambios('parametro_editado', 'Tiempo de inactividad (TIME OUT)', { minutosSesion: e().minutos }, { minutosSesion: m }, [], { quien: f.u });
        e().minutos = m;
        return { mensaje: `Tiempo de inactividad actualizado: ${m} minutos. Aplica desde la siguiente acción de cada usuario.` };
      },
    ],
    [
      'GET',
      /^\/api\/usuarios\/roles$/,
      () => ({
        bases: BASES.map((id) => ({ id, nombre: e().perfiles.find((p) => p.id === id).nombre, pantallas: elegibles(id).map((p) => ({ codigo: p.codigo, titulo: p.titulo })) })),
        roles: [...e().roles]
          .sort((a, b) => Number(b.activo) - Number(a.activo) || a.nombre.localeCompare(b.nombre, 'es'))
          .map((r) => ({ id: r.id, nombre: r.nombre, descripcion: r.descripcion, baseId: r.baseId, activo: r.activo, pantallas: r.pantallas, usuarios: usuariosActivosCon(r.id) })),
      }),
    ],
    [
      'POST',
      /^\/api\/usuarios\/roles$/,
      (f, b) => {
        const base = Number.isInteger(b.baseId) ? b.baseId : null;
        const d = datosRol(b, 0, base);
        const id = e().siguienteRol++;
        e().roles.push({ id, ...d, baseId: base, activo: true });
        log.registrar('rol_creado', `Rol ${id} «${d.nombre}» con base ${e().perfiles.find((p) => p.id === base).codigo}; pantallas: ${d.pantallas.join(', ')}`, { quien: f.u });
        return { estado: 201, cuerpo: { id, mensaje: 'Rol creado. Asígnelo en el formulario de cada usuario.' } };
      },
    ],
    [
      'PUT',
      /^\/api\/usuarios\/roles\/(\d+)$/,
      (f, b, id) => {
        const rol = rolPorId(id);
        if (b.baseId != null && b.baseId !== rol.baseId) {
          throw new Negocio('El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.');
        }
        const antes = { nombre: rol.nombre, descripcion: rol.descripcion, pantallas: rol.pantallas };
        Object.assign(rol, datosRol(b, id, rol.baseId));
        log.registrarCambios('rol_editado', `Rol ${id}`, antes, { nombre: rol.nombre, descripcion: rol.descripcion, pantallas: rol.pantallas }, [], { quien: f.u });
        return { mensaje: 'Rol actualizado. Aplica desde la siguiente acción de sus usuarios.' };
      },
    ],
    [
      'PUT',
      /^\/api\/usuarios\/roles\/(\d+)\/estado$/,
      (f, b, id) => {
        const rol = rolPorId(id);
        rol.activo = activo(b);
        log.registrar('rol_estado', `Rol ${id} ${rol.activo ? 'activado' : 'inactivado'} (${usuariosActivosCon(id)} usuarios activos lo tienen)`, { quien: f.u });
        return { mensaje: `Rol ${rol.activo ? 'activado' : 'inactivado'} (${usuariosActivosCon(id)} usuarios activos lo tienen).` };
      },
    ],
    [
      'PUT',
      /^\/api\/usuarios\/perfiles\/(\d+)$/,
      (f, b, id) => {
        const n = texto(b.nombre);
        const d = vacioANulo(b.descripcion);
        if (n.length < 3 || n.length > 60) throw new Negocio('El nombre del perfil debe tener de 3 a 60 caracteres.');
        if (d !== null && d.length > 200) throw new Negocio('La descripción tiene máximo 200 caracteres.');
        const p = e().perfiles.find((x) => x.id === id);
        if (!p) throw new Negocio('El perfil no existe.', { estado: 404 });
        if (nombresPerfiles().some(([i, x]) => i !== id && x.toLowerCase() === n.toLowerCase())) throw new Negocio('Ya existe otro perfil con ese nombre.');
        log.registrarCambios('perfil_editado', `Perfil ${id}`, { nombre: p.nombre }, { nombre: n }, [], { quien: f.u });
        Object.assign(p, { nombre: n, descripcion: d });
        return { mensaje: 'Perfil actualizado.' };
      },
    ],
  ];

  /** El manejador de `método camino`, o null si no es de /api/usuarios. */
  return function rutaUsuarios(metodo, camino) {
    for (const [m, patron, manejar] of rutas) {
      const x = patron.exec(camino);
      if (!x || m !== metodo) continue;
      return async (req, r, cuerpo) => {
        const f = administrador(req, r);
        if (!f) return;
        try {
          const res = await manejar(f, cuerpo, x[1] === undefined ? null : Number(x[1]));
          if (res && typeof res.estado === 'number' && 'cuerpo' in res) r.enviar(res.estado, res.cuerpo);
          else r.json(res);
        } catch (err) {
          if (!(err instanceof Negocio)) throw err;
          r.problema(err.estado, err.message, err.campo ? { campo: err.campo } : {});
        }
      };
    }
    return null;
  };
}

/** Nombres visibles de los perfiles asignados (para la sesión): los base con su nombre vigente y los roles con el suyo. */
export function nombresAsignados(ids, perfiles, roles) {
  return ids.map((id) => perfiles.find((p) => p.id === id)?.nombre ?? roles.find((r) => r.id === id)?.nombre).filter(Boolean);
}
