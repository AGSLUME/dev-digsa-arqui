// Pruebas de /api/usuarios/** del mock (npm run test:mock): reglas y textos del monolito, sin red (contexto falso).
import assert from 'node:assert/strict';
import { beforeEach, test } from 'node:test';
import { PERFILES, usuariosIniciales } from './datos.mjs';
import { bogota, crearRutasUsuarios, normalizarTelefono } from './usuarios.mjs';

let estado;
let quien;
let enviados;
let rutas;

beforeEach(() => {
  estado = { minutos: 10, usuarios: usuariosIniciales(), perfiles: PERFILES.map((p) => ({ ...p })), roles: [], siguienteRol: 101, sesiones: new Map(), huellas: new Map(), enlaces: new Map() };
  quien = estado.usuarios[0]; // admin.angular
  enviados = [];
  rutas = crearRutasUsuarios({
    estado: () => estado,
    filtroSesion: () => ({ s: {}, u: quien }),
    sesionAbierta: (u) => estado.huellas.has(u.id),
    bloqueado: (u) => u.bloqueadoHasta > Date.now(),
    cerrar: (s, motivo) => (s.cerrada = motivo),
    enviarEnlace: async (u, asunto) => enviados.push({ para: u.correo, asunto }),
    errorPolitica: (c) => (c.length < 12 ? 'La contraseña debe tener mínimo 12 caracteres.' : null),
    usuarioDe: (id) => estado.usuarios.find((u) => u.id === id),
  });
});

/** Llama a la ruta y devuelve { estado, cuerpo } como lo vería la pantalla. */
async function llamar(metodo, camino, cuerpo = {}) {
  const manejador = rutas(metodo, camino);
  assert.ok(manejador, `no hay ruta ${metodo} ${camino}`);
  let res;
  const r = {
    json: (c) => (res = { estado: 200, cuerpo: c }),
    enviar: (e, c) => (res = { estado: e, cuerpo: c }),
    problema: (e, mensaje, extra = {}) => (res = { estado: e, cuerpo: { mensaje, ...extra } }),
  };
  await manejador({}, r, cuerpo);
  return res;
}

const auditor = (extra = {}) => ({ nombre: 'Auditor Uno de Prueba', usuario: 'auditor.nuevo', correo: 'auditor.nuevo@angular.prueba.local', documento: '80123456', perfiles: [3], ...extra });

test('solo el Administrador: otro perfil recibe 403 «No tiene permiso para esta acción.»', async () => {
  quien = estado.usuarios[1]; // coordinador
  assert.deepEqual(await llamar('GET', '/api/usuarios'), { estado: 403, cuerpo: { mensaje: 'No tiene permiso para esta acción.' } });
  assert.equal(rutas('GET', '/api/logs'), null);
});

test('GET /api/usuarios: filas del contrato, perfiles activos (sin Consulta), ESM, regionales y fuerzas', async () => {
  const { estado: e, cuerpo } = await llamar('GET', '/api/usuarios');
  assert.equal(e, 200);
  assert.deepEqual(Object.keys(cuerpo), ['usuarios', 'perfiles', 'puntos', 'regionales', 'fuerzas']);
  const fila = cuerpo.usuarios.find((u) => u.usuario === 'inicial.angular');
  assert.equal(fila.claveInicial, true);
  assert.equal(fila.bloqueado, false);
  assert.deepEqual(fila.perfiles, [3]);
  assert.ok(!cuerpo.perfiles.some((p) => p.nombre === 'Consulta'));
  assert.deepEqual(cuerpo.perfiles[0], { id: 1, nombre: 'Administrador', descripcion: 'Administra usuarios, logs y la exportación de datos.', baseId: null });
  assert.deepEqual(cuerpo.fuerzas.map((f) => f.codigo), ['EJC', 'ARC', 'FAC']);
});

test('crear sin contraseña inicial envía el enlace de bienvenida; con ella, no envía correo', async () => {
  const a = await llamar('POST', '/api/usuarios', auditor());
  assert.equal(a.estado, 201);
  assert.equal(a.cuerpo.mensaje, 'Usuario creado. Se le envió el enlace para definir su contraseña.');
  assert.deepEqual(enviados, [{ para: 'auditor.nuevo@angular.prueba.local', asunto: 'Bienvenido a la Suite DIGSA' }]);
  const b = await llamar('POST', '/api/usuarios', auditor({ usuario: 'otro.nuevo', correo: 'otro.nuevo@angular.prueba.local', claveInicial: 'Inicial.Asignada.2026' }));
  assert.equal(b.cuerpo.mensaje, 'Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: deberá cambiarla en su primer ingreso.');
  assert.equal(enviados.length, 1);
  assert.equal(estado.usuarios.at(-1).claveInicial, true);
});

test('si el correo no sale, 201 con correoOk=false y el texto del monolito', async () => {
  rutas = crearRutasUsuarios({
    estado: () => estado,
    filtroSesion: () => ({ s: {}, u: quien }),
    sesionAbierta: () => false,
    bloqueado: () => false,
    cerrar: () => undefined,
    enviarEnlace: async () => {
      throw new Error('SMTP caído');
    },
    errorPolitica: () => null,
    usuarioDe: (id) => estado.usuarios.find((u) => u.id === id),
  });
  const { estado: e, cuerpo } = await llamar('POST', '/api/usuarios', auditor());
  assert.equal(e, 201);
  assert.equal(cuerpo.correoOk, false);
  assert.equal(cuerpo.mensaje, 'Usuario creado, pero NO se pudo enviar el correo (SMTP caído). Use «Restablecer contraseña» cuando el correo funcione.');
});

test('validaciones de guardar en el orden del monolito (el teléfono, con campo, antes que el documento del auditor)', async () => {
  const error = async (d) => (await llamar('POST', '/api/usuarios', d)).cuerpo;
  assert.deepEqual(await error(auditor({ documento: null, telefono: '601 000 0000' })), {
    mensaje: 'El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de las alertas críticas.',
    campo: 'telefono',
  });
  assert.equal((await error(auditor({ nombre: 'Al' }))).mensaje, 'Escriba el nombre completo (mínimo 3 caracteres).');
  assert.match((await error(auditor({ usuario: 'Con Espacio' }))).mensaje, /^El usuario debe tener de 3 a 50 caracteres/);
  assert.equal((await error(auditor({ correo: 'sin-arroba' }))).mensaje, 'Escriba un correo válido.');
  assert.equal((await error(auditor({ perfiles: [] }))).mensaje, 'Marque al menos un perfil.');
  assert.equal((await error(auditor({ perfiles: [4] }))).mensaje, 'Hay un perfil no válido.');
  assert.equal((await error(auditor({ documento: '' }))).mensaje, 'El documento es obligatorio para los auditores: va en la notificación previa de la visita (decisión 26).');
  assert.equal((await error(auditor({ perfiles: [6] }))).mensaje, 'El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).');
  assert.equal((await error(auditor({ perfiles: [7] }))).mensaje, 'El perfil ESM necesita el establecimiento (ESM activo).');
  assert.equal((await error(auditor({ perfiles: [8, 2] }))).mensaje, 'Los perfiles del OPL (entidad auditada) no se combinan con los de la interventoría.');
  assert.equal((await error(auditor({ perfiles: [5, 6], alcanceFuerza: 'EJC' }))).mensaje, 'Un usuario tiene un solo perfil de consulta u OPL (DIGSA, Sanidad): cada uno ve un alcance distinto.');
  assert.equal((await error(auditor({ claveInicial: 'corta' }))).mensaje, 'Contraseña inicial: La contraseña debe tener mínimo 12 caracteres.');
  assert.equal((await error(auditor({ usuario: 'auditor.angular' }))).mensaje, 'Ya existe un usuario con ese nombre de usuario.');
  assert.equal((await error(auditor({ correo: 'auditor.angular@angular.prueba.local' }))).mensaje, 'Ya existe un usuario con ese correo.');
  assert.equal(estado.usuarios.length, 5);
});

test('editar: «Usuario actualizado.», teléfono normalizado y no quitarse a sí mismo el Administrador', async () => {
  const a = await llamar('PUT', '/api/usuarios/3', auditor({ usuario: 'auditor.angular', correo: 'auditor.angular@angular.prueba.local', telefono: '300 123 4567' }));
  assert.deepEqual(a, { estado: 200, cuerpo: { id: 3, creado: false, correoOk: true, mensaje: 'Usuario actualizado.', enlaceLocal: null } });
  assert.equal(estado.usuarios[2].telefono, '+573001234567');
  const b = await llamar('PUT', '/api/usuarios/1', { nombre: 'Administradora Ficticia', usuario: 'admin.angular', correo: 'admin.angular@angular.prueba.local', perfiles: [2] });
  assert.equal(b.cuerpo.mensaje, 'No puede quitarse a sí mismo el perfil Administrador.');
  assert.equal((await llamar('PUT', '/api/usuarios/999', auditor())).estado, 404);
});

test('estado, desbloquear, cerrar sesión, contraseña inicial y restablecer: mensajes del monolito', async () => {
  assert.equal((await llamar('PUT', '/api/usuarios/1/estado', { activo: false })).cuerpo.mensaje, 'No puede inactivar su propio usuario.');
  assert.equal((await llamar('PUT', '/api/usuarios/3/estado', { activo: false })).cuerpo.mensaje, 'Usuario inactivado.');
  assert.equal((await llamar('PUT', '/api/usuarios/3/estado', { activo: true })).cuerpo.mensaje, 'Usuario activado.');
  assert.equal((await llamar('PUT', '/api/usuarios/3/estado', {})).estado, 400);

  estado.usuarios[2].bloqueadoHasta = Date.now() + 60_000;
  assert.equal((await llamar('POST', '/api/usuarios/3/desbloquear')).cuerpo.mensaje, 'Usuario desbloqueado.');
  assert.equal((await llamar('POST', '/api/usuarios/3/desbloquear')).cuerpo.mensaje, 'El usuario no estaba bloqueado.');

  const sesion = {};
  estado.sesiones.set('s3', sesion);
  estado.huellas.set(3, 's3');
  assert.equal((await llamar('POST', '/api/usuarios/3/cerrar-sesion')).cuerpo.mensaje, 'Sesión cerrada.');
  assert.equal(sesion.cerrada, 'cerrada');
  estado.huellas.delete(3);
  assert.equal((await llamar('POST', '/api/usuarios/3/cerrar-sesion')).cuerpo.mensaje, 'El usuario no tenía una sesión abierta.');

  assert.equal((await llamar('POST', '/api/usuarios/3/clave-inicial', { clave: 'corta' })).cuerpo.mensaje, 'Contraseña inicial: La contraseña debe tener mínimo 12 caracteres.');
  assert.equal(
    (await llamar('POST', '/api/usuarios/3/clave-inicial', { clave: 'Inicial.Nueva.2026' })).cuerpo.mensaje,
    'Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar.',
  );
  assert.equal(estado.usuarios[2].claveInicial, true);

  assert.equal((await llamar('POST', '/api/usuarios/3/restablecer')).cuerpo.mensaje, 'Se envió al correo del usuario el enlace para definir una contraseña nueva.');
  assert.equal(enviados.at(-1).asunto, 'Defina una contraseña nueva');
});

test('seguridad: GET con el máximo 480; PUT valida con campo minutosSesion y cambia el TIME OUT', async () => {
  assert.deepEqual((await llamar('GET', '/api/usuarios/seguridad')).cuerpo, { minutosSesion: 10, minutosMaximo: 480 });
  assert.deepEqual((await llamar('PUT', '/api/usuarios/seguridad', {})).cuerpo, { mensaje: 'Escriba los minutos de inactividad.', campo: 'minutosSesion' });
  assert.equal((await llamar('PUT', '/api/usuarios/seguridad', { minutosSesion: 481 })).cuerpo.mensaje, 'Los minutos de inactividad deben ser un número entero de 1 a 480.');
  assert.equal((await llamar('PUT', '/api/usuarios/seguridad', { minutosSesion: 1.5 })).estado, 422);
  assert.equal(
    (await llamar('PUT', '/api/usuarios/seguridad', { minutosSesion: 3 })).cuerpo.mensaje,
    'Tiempo de inactividad actualizado: 3 minutos. Aplica desde la siguiente acción de cada usuario.',
  );
  assert.equal(estado.minutos, 3);
});

test('roles: bases elegibles (sin Administrador ni Auditor), crear, validar, editar sin cambiar la base y cambiar estado', async () => {
  const cat = (await llamar('GET', '/api/usuarios/roles')).cuerpo;
  assert.deepEqual(cat.bases.map((b) => b.nombre), ['Coordinador', 'DIGSA', 'Sanidad', 'ESM', 'OPL nacional', 'OPL regional', 'Analista']);
  const coord = cat.bases[0].pantallas.map((p) => p.titulo);
  assert.ok(coord.includes('Dashboard de riesgo') && coord.includes('Agenda de visitas'));
  assert.ok(!coord.includes('Inicio') && !coord.includes('Visita') && !coord.includes('Usuarios'));

  const rol = { nombre: 'Gestor del riesgo', baseId: 2, pantallas: ['RIESGO', 'DASHBOARD'] };
  assert.equal((await llamar('POST', '/api/usuarios/roles', { ...rol, baseId: 1 })).cuerpo.mensaje, 'El perfil base no es válido: un rol adicional no deriva del Administrador ni del Auditor.');
  assert.equal((await llamar('POST', '/api/usuarios/roles', { ...rol, pantallas: [] })).cuerpo.mensaje, 'Marque al menos una pantalla para el rol.');
  assert.equal((await llamar('POST', '/api/usuarios/roles', { ...rol, pantallas: ['NO_EXISTE'] })).cuerpo.mensaje, 'Hay una pantalla no válida.');
  assert.equal((await llamar('POST', '/api/usuarios/roles', { ...rol, pantallas: ['USUARIOS'] })).cuerpo.mensaje, 'La pantalla «Usuarios» no es del perfil base Coordinador.');
  assert.equal((await llamar('POST', '/api/usuarios/roles', { ...rol, nombre: 'auditor' })).cuerpo.mensaje, 'Ya existe un perfil o rol con ese nombre.');
  assert.deepEqual(await llamar('POST', '/api/usuarios/roles', rol), { estado: 201, cuerpo: { id: 101, mensaje: 'Rol creado. Asígnelo en el formulario de cada usuario.' } });

  assert.equal((await llamar('PUT', '/api/usuarios/roles/101', { ...rol, baseId: 5 })).cuerpo.mensaje, 'El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.');
  assert.equal((await llamar('PUT', '/api/usuarios/roles/101', { ...rol, descripcion: 'Solo riesgo' })).cuerpo.mensaje, 'Rol actualizado. Aplica desde la siguiente acción de sus usuarios.');
  assert.equal((await llamar('PUT', '/api/usuarios/roles/999', rol)).estado, 404);

  // un usuario con el rol: sin perfiles directos
  const conRol = { nombre: 'Gestor de Riesgo de Prueba', usuario: 'gestor.riesgo', correo: 'gestor.riesgo@angular.prueba.local', perfiles: [101, 2] };
  assert.equal((await llamar('POST', '/api/usuarios', conRol)).cuerpo.mensaje, 'Un usuario con un rol adicional no tiene además perfiles: el rol ya trae el alcance de su perfil base.');
  assert.equal((await llamar('POST', '/api/usuarios', { ...conRol, perfiles: [101], claveInicial: 'Inicial.E2E.Roles.2026' })).estado, 201);
  assert.equal((await llamar('PUT', '/api/usuarios/roles/101/estado', { activo: false })).cuerpo.mensaje, 'Rol inactivado (1 usuarios activos lo tienen).');
  const lista = (await llamar('GET', '/api/usuarios/roles')).cuerpo.roles;
  assert.deepEqual(lista, [{ id: 101, nombre: 'Gestor del riesgo', descripcion: 'Solo riesgo', baseId: 2, activo: false, pantallas: ['DASHBOARD', 'RIESGO'], usuarios: 1 }]);
});

test('renombrar un perfil: validaciones, 404 y el nombre nuevo en el catálogo', async () => {
  assert.equal((await llamar('PUT', '/api/usuarios/perfiles/2', { nombre: 'Co' })).cuerpo.mensaje, 'El nombre del perfil debe tener de 3 a 60 caracteres.');
  assert.equal((await llamar('PUT', '/api/usuarios/perfiles/2', { nombre: 'auditor' })).cuerpo.mensaje, 'Ya existe otro perfil con ese nombre.');
  assert.equal((await llamar('PUT', '/api/usuarios/perfiles/99', { nombre: 'Sin perfil' })).estado, 404);
  assert.equal((await llamar('PUT', '/api/usuarios/perfiles/2', { nombre: 'Coordinación', descripcion: null })).cuerpo.mensaje, 'Perfil actualizado.');
  assert.equal((await llamar('GET', '/api/usuarios')).cuerpo.perfiles[1].nombre, 'Coordinación');
});

test('teléfono (reglas/Alertas.normalizarTelefono) y hora de Bogotá', () => {
  assert.equal(normalizarTelefono('+57 300 000 0000'), '+573000000000');
  assert.equal(normalizarTelefono('(300) 000-0000'), '+573000000000');
  assert.equal(normalizarTelefono('573001234567'), '+573001234567');
  assert.equal(normalizarTelefono('601 000 0000'), null);
  assert.equal(bogota(Date.UTC(2026, 10, 5, 3, 7)), '2026-11-04 22:07');
});
