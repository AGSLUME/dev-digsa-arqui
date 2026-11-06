// Datos FICTICIOS del mock de seguridad (ADR-010: dominio propio de la sesión, @angular.prueba.local). Ninguna persona real.
// Las claves de los usuarios de prueba solo sirven contra este mock; se pueden cambiar con DIGSA_MOCK_CLAVE_<USUARIO>.

/** Perfiles base de reglas/Perfil.java (id fijo). Consulta (4) se retiró (decisión 20): queda inactivo y no se asigna. */
export const PERFILES = [
  [1, 'Administrador', 'Administra usuarios, logs y la exportación de datos.'],
  [2, 'Coordinador', 'Programa las visitas y administra los maestros.'],
  [3, 'Auditor', 'Hace las visitas que se le asignan.'],
  [4, 'Consulta', null, false],
  [5, 'DIGSA', 'Consulta nacional de la DIGSA.'],
  [6, 'Sanidad', 'Consulta de su fuerza.'],
  [7, 'ESM', 'Consulta de su establecimiento.'],
  [8, 'OPL nacional', 'Responde los hallazgos (nacional).'],
  [9, 'OPL regional', 'Responde los hallazgos de su regional.'],
  [10, 'Analista', 'Consulta nacional y analítica.'],
].map(([id, nombre, descripcion, activo = true]) => ({ id, codigo: nombre, nombre, descripcion, activo }));

/** Pantallas de reglas/Pantallas.java del monolito, en su orden: [ruta, título, grupo, perfiles ('*' = cualquiera con sesión), oculta]. */
export const PANTALLAS = [
  ['inicio', 'Inicio', '', '*'],
  ['agenda', 'Agenda de visitas', 'Visitas', ['Coordinador']],
  ['propuesta', 'Propuesta de agenda', 'Visitas', ['Coordinador']],
  ['mis-visitas', 'Mis visitas', 'Visitas', ['Auditor']],
  ['hallazgos', 'Prestadores con hallazgos', 'Visitas', ['Auditor', 'Coordinador', 'DIGSA', 'Sanidad', 'ESM', 'Analista']],
  ['calendario', 'Calendario de visitas', 'Visitas', ['DIGSA', 'Sanidad', 'ESM', 'Analista']],
  ['solicitudes', 'Solicitudes de visita', 'Visitas', ['Coordinador', 'DIGSA', 'Sanidad']],
  ['prorrogas', 'Prórrogas del plan', 'Visitas', ['Coordinador', 'DIGSA']],
  ['opl', 'Respuesta a hallazgos', 'Visitas', ['OPL nacional', 'OPL regional']],
  ['riesgo', 'Dashboard de riesgo', 'Visitas', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['dashboard', 'Dashboard', 'Visitas', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['mapas', 'Mapas de visitas', 'Visitas', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['reportes', 'Reportes de visitas', 'Visitas', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['maestros', 'Puntos y auditores', 'Configuración', ['Coordinador']],
  ['catalogos', 'Catálogos', 'Configuración', ['Coordinador']],
  ['alertas', 'Alertas', 'Configuración', ['Coordinador']],
  ['presupuesto', 'Presupuesto', 'Presupuesto', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['usuarios', 'Usuarios', 'Configuración', []],
  ['exportacion', 'Exportación de datos', 'Configuración', []],
  ['logs', 'Logs del sistema', 'Configuración', []],
  ['correos', 'Correos', 'Configuración', []],
  ['visita', 'Visita', '', ['Auditor', 'Coordinador'], true],
  ['analitica/cargues', 'Cargues del OPL', 'Presupuesto', ['Analista', 'Coordinador']],
  ['homologacion', 'Homologación', 'Presupuesto', ['Analista', 'Coordinador']],
  ['analitica', 'Analítica farmacéutica', 'Presupuesto', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad', 'ESM']],
  ['analitica/trazabilidad', 'Trazabilidad de cifras', 'Presupuesto', ['Coordinador', 'Analista', 'DIGSA']],
  ['analitica/alertas', 'Alertas analíticas', 'Presupuesto', ['Coordinador', 'Analista', 'DIGSA', 'Sanidad']],
].map(([ruta, titulo, grupo, exige, oculta = false]) => ({ ruta, codigo: ruta.toUpperCase().replace(/[/-]/g, '_'), titulo, grupo, exige, oculta }));

/** Perfiles que pueden ser base de un rol adicional (reglas/Roles.BASES): ni Administrador, ni Consulta, ni Auditor. */
export const BASES = [2, 5, 6, 7, 8, 9, 10];

const codigoBase = (id) => PERFILES.find((p) => p.id === id)?.codigo;

/** Pantallas que se pueden dar a un rol con esa base (Roles.elegibles): las de su base que van en el menú, sin Inicio ni ocultas. */
export function elegibles(baseId) {
  if (!BASES.includes(baseId)) return [];
  const c = codigoBase(baseId);
  return PANTALLAS.filter((p) => p.exige !== '*' && !p.oculta && p.exige.includes(c));
}

/**
 * Pantallas a las que entra el usuario (Roles.permitidas): las de sus perfiles directos (el Administrador entra a todas; '*' cualquiera
 * con sesión) más, de cada rol adicional ACTIVO, las marcadas que sigan siendo elegibles para su base.
 */
export function permitidas(ids, roles) {
  const directos = ids.map(codigoBase).filter(Boolean);
  const deRoles = new Set(
    roles.filter((r) => r.activo && ids.includes(r.id)).flatMap((r) => elegibles(r.baseId).filter((p) => r.pantallas.includes(p.codigo)).map((p) => p.codigo)),
  );
  return PANTALLAS.filter((p) => directos.includes('Administrador') || p.exige === '*' || p.exige.some((x) => directos.includes(x)) || deRoles.has(p.codigo));
}

/** Establecimientos (ESM activos), regionales y fuerzas para el alcance de ESM, OPL regional y Sanidad. Nombres inventados. */
export const PUNTOS = [
  { id: 501, nombre: 'Establecimiento Ficticio Norte', sigla: 'EFN', fuerza: 'ARC' },
  { id: 502, nombre: 'Establecimiento Ficticio Centro', sigla: 'EFC', fuerza: 'EJC' },
  { id: 503, nombre: 'Establecimiento Ficticio Sur', sigla: null, fuerza: 'FAC' },
];
export const REGIONALES = [
  { id: 31, nombre: 'Regional Ficticia Andina' },
  { id: 32, nombre: 'Regional Ficticia Caribe' },
];
export const FUERZAS = [
  { codigo: 'EJC', nombre: 'DISAN EJC (Ejército)' },
  { codigo: 'ARC', nombre: 'DISAN ARC (Armada)' },
  { codigo: 'FAC', nombre: 'JEFSA FAC (Fuerza Aeroespacial)' },
];

const clave = (usuario, porOmision) => process.env[`DIGSA_MOCK_CLAVE_${usuario.toUpperCase().replace(/\W/g, '_')}`] ?? porOmision;

/**
 * Usuarios de prueba. `perfiles`: ids (perfiles base y roles adicionales). `claveInicial`: la asignó el administrador y se cambia al
 * primer ingreso (paso `cambiar_clave`). `definir`: nunca definió clave (paso `definir_clave`, se envía el enlace).
 */
export function usuariosIniciales() {
  return [
    { id: 1, usuario: 'admin.angular', nombre: 'Administradora Ficticia', perfiles: [1], clave: clave('admin.angular', 'Admin.Angular.Prueba.2026') },
    { id: 2, usuario: 'coordinador.angular', nombre: 'Coordinador Ficticio', perfiles: [2], clave: clave('coordinador.angular', 'Coord.Angular.Prueba.2026') },
    { id: 3, usuario: 'auditor.angular', nombre: 'Auditora Ficticia', perfiles: [3], documento: '1000000003', clave: clave('auditor.angular', 'Auditor.Angular.Prueba.2026') },
    { id: 4, usuario: 'inicial.angular', nombre: 'Usuario Inicial Ficticio', perfiles: [3], documento: '1000000004', clave: clave('inicial.angular', 'Inicial.Angular.Prueba.2026'), claveInicial: true },
    { id: 5, usuario: 'definir.angular', nombre: 'Usuario Sin Clave Ficticio', perfiles: [3], documento: '1000000005', clave: clave('definir.angular', 'Definir.Angular.Prueba.2026'), definir: true },
  ].map((u) => ({
    documento: null,
    telefono: null,
    alcanceFuerza: null,
    alcancePunto: null,
    alcanceRegional: null,
    ultimoIngreso: null,
    claveInicial: false,
    definir: false,
    ...u,
    correo: `${u.usuario}@angular.prueba.local`,
    activo: true,
    intentos: 0,
    bloqueadoHasta: 0,
  }));
}
