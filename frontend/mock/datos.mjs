// Datos FICTICIOS del mock de seguridad (ADR-010: dominio propio de la sesión, @angular.prueba.local). Ninguna persona real.
// Las claves de los usuarios de prueba solo sirven contra este mock; se pueden cambiar con DIGSA_MOCK_CLAVE_<USUARIO>.

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
];

/** Regla de Pantallas.java: el Administrador entra a todas; sin perfiles exigidos ('*'), cualquiera con sesión; si no, alguno de ellos. */
export function permitidas(perfiles) {
  return PANTALLAS.filter(([, , , exige]) => perfiles.includes('Administrador') || exige === '*' || exige.some((p) => perfiles.includes(p)));
}

const clave = (usuario, porOmision) => process.env[`DIGSA_MOCK_CLAVE_${usuario.toUpperCase().replace(/\W/g, '_')}`] ?? porOmision;

/**
 * Usuarios de prueba. `claveInicial`: la asignó el administrador y se cambia al primer ingreso (paso `cambiar_clave`). `definir`: nunca
 * definió clave (paso `definir_clave`, se envía el enlace). Los id siguen el estilo del monolito (numéricos).
 */
export function usuariosIniciales() {
  return [
    { id: 1, usuario: 'admin.angular', nombre: 'Administradora Ficticia', perfiles: ['Administrador'], clave: clave('admin.angular', 'Admin.Angular.Prueba.2026') },
    { id: 2, usuario: 'coordinador.angular', nombre: 'Coordinador Ficticio', perfiles: ['Coordinador'], clave: clave('coordinador.angular', 'Coord.Angular.Prueba.2026') },
    { id: 3, usuario: 'auditor.angular', nombre: 'Auditora Ficticia', perfiles: ['Auditor'], clave: clave('auditor.angular', 'Auditor.Angular.Prueba.2026') },
    { id: 4, usuario: 'inicial.angular', nombre: 'Usuario Inicial Ficticio', perfiles: ['Auditor'], clave: clave('inicial.angular', 'Inicial.Angular.Prueba.2026'), claveInicial: true },
    { id: 5, usuario: 'definir.angular', nombre: 'Usuario Sin Clave Ficticio', perfiles: ['Auditor'], clave: clave('definir.angular', 'Definir.Angular.Prueba.2026'), definir: true },
  ].map((u) => ({ ...u, correo: `${u.usuario}@angular.prueba.local`, activo: true, intentos: 0, bloqueadoHasta: 0 }));
}
