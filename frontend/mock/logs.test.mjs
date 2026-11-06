// Pruebas de la bitácora y /api/logs/** del mock (npm run test:mock): reglas y textos del monolito, sin red (contexto falso).
import assert from 'node:assert/strict';
import { beforeEach, test } from 'node:test';
import { PERFILES, usuariosIniciales } from './datos.mjs';
import { crearBitacora, crearRutasLogs } from './logs.mjs';

let estado;
let quien;
let enviados;
let bitacora;
let rutas;
let falla;

beforeEach(() => {
  estado = { usuarios: usuariosIniciales(), perfiles: PERFILES.map((p) => ({ ...p })), roles: [], bitacora: [], siguienteLog: 0 };
  quien = estado.usuarios[0];
  enviados = [];
  falla = null;
  bitacora = crearBitacora(() => estado);
  rutas = crearRutasLogs({
    estado: () => estado,
    filtroSesion: () => ({ s: {}, u: quien }),
    bitacora,
    enviarCorreo: async (m) => {
      if (falla) throw new Error(falla);
      enviados.push(m);
    },
    enmascarar: (c) => c.replace(/^(.).*(.)@/, '$1***$2@'),
    dominios: ['angular.prueba.local'],
  });
});

async function llamar(metodo, camino, cuerpo = {}) {
  const manejador = rutas(metodo, camino.split('?')[0]);
  assert.ok(manejador, `no hay ruta ${metodo} ${camino}`);
  let res;
  const r = {
    json: (c) => (res = { estado: 200, cuerpo: c }),
    archivo: (a) => (res = { estado: 200, archivo: a }),
    problema: (e, mensaje) => (res = { estado: e, cuerpo: { mensaje } }),
  };
  await manejador({ url: camino }, r, cuerpo);
  return res;
}

const hoy = () => new Date(Date.now() - 5 * 3_600_000).toISOString().slice(0, 10);

test('solo el Administrador: otro perfil recibe 403', async () => {
  quien = estado.usuarios[1];
  assert.deepEqual(await llamar('GET', '/api/logs'), { estado: 403, cuerpo: { mensaje: 'No tiene permiso para esta acción.' } });
});

test('registrarCambios: solo lo que cambió, y los datos personales sin valores (Ley 1581)', () => {
  bitacora.registrarCambios('usuario_editado', 'Usuario x editado', { nombre: 'A', usuario: 'x', perfiles: [2] }, { nombre: 'B', usuario: 'xb', perfiles: [2] }, ['nombre'], { quien });
  const [e] = estado.bitacora;
  assert.equal(e.operacion, 'actualizacion');
  assert.equal(e.usuario, 'admin.angular');
  assert.deepEqual(e.cambios, [
    { campo: 'nombre', antes: null, despues: null, oculto: true },
    { campo: 'usuario', antes: 'x', despues: 'xb', oculto: false },
  ]);
});

test('lista: fila del contrato (fecha con segundos en Bogotá, fechaLlave con zona, servicio), filtros y validaciones', async () => {
  bitacora.registrar('login', 'Ingreso de admin.angular', { quien, modulo: 'auth' });
  bitacora.registrar('usuario_creado', 'Usuario nuevo.x creado; perfiles: 3', { quien });
  const { cuerpo } = await llamar('GET', '/api/logs');
  assert.equal(cuerpo.filas.length, 2);
  assert.equal(cuerpo.filas[0].tipo, 'usuario_creado');
  assert.match(cuerpo.filas[0].fecha, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  assert.match(cuerpo.filas[0].fechaLlave, /-05:00$/);
  assert.equal(cuerpo.filas[0].servicio, 'seguridad');
  assert.equal(cuerpo.hasta, hoy());
  assert.equal(cuerpo.siguiente, null);

  assert.equal((await llamar('GET', '/api/logs?tipo=LOGIN')).cuerpo.filas.length, 1);
  assert.equal((await llamar('GET', '/api/logs?modulo=usuarios&operacion=insercion')).cuerpo.filas.length, 1);
  assert.equal((await llamar('GET', '/api/logs?texto=NUEVO.x')).cuerpo.filas.length, 1);
  assert.equal((await llamar('GET', '/api/logs?tipo=no_existe')).cuerpo.filas.length, 0);
  assert.equal((await llamar('GET', '/api/logs?tipo=con espacio')).cuerpo.mensaje, 'El tipo y el módulo son palabras sin espacios (p. ej. usuario_editado o usuarios).');
  assert.equal((await llamar('GET', '/api/logs?texto=x&desde=2026-01-01&hasta=2026-06-01')).cuerpo.mensaje, 'Para buscar por texto, use un periodo de máximo 92 días.');
  assert.equal((await llamar('GET', '/api/logs?despuesFecha=no-es-fecha&despuesId=1')).cuerpo.mensaje, 'La página pedida no es válida.');
  // Fechas invertidas se intercambian
  const inv = (await llamar('GET', '/api/logs?desde=2026-11-30&hasta=2026-11-01')).cuerpo;
  assert.deepEqual([inv.desde, inv.hasta], ['2026-11-01', '2026-11-30']);
});

test('páginas de 100 con cursor: la segunda sigue donde terminó la primera, sin repetir', async () => {
  for (let i = 0; i < 130; i++) bitacora.registrar('login', `Ingreso ${i}`, { quien, modulo: 'auth' });
  const p1 = (await llamar('GET', '/api/logs')).cuerpo;
  assert.equal(p1.filas.length, 100);
  assert.ok(p1.siguiente);
  const p2 = (await llamar('GET', `/api/logs?despuesFecha=${encodeURIComponent(p1.siguiente.fecha)}&despuesId=${p1.siguiente.id}`)).cuerpo;
  assert.equal(p2.filas.length, 30);
  assert.equal(p2.siguiente, null);
  assert.equal(new Set([...p1.filas, ...p2.filas].map((f) => f.id)).size, 130);
});

test('opciones: las 6 operaciones, todos los usuarios y los módulos de la bitácora', async () => {
  bitacora.registrar('login', 'Ingreso', { quien, modulo: 'auth' });
  const { cuerpo } = await llamar('GET', '/api/logs/opciones');
  assert.deepEqual(cuerpo.operaciones, ['insercion', 'actualizacion', 'eliminacion', 'consulta', 'ingreso', 'salida']);
  assert.equal(cuerpo.usuarios.length, 5);
  assert.deepEqual(cuerpo.modulos, ['auth']);
  assert.equal(cuerpo.correoDefecto, '');
});

test('exportar: Excel (zip válido con la hoja) o CSV, con el nombre logs_auditoria_yyyyMMdd_HHmm', async () => {
  bitacora.registrar('login', 'Ingreso de admin.angular', { quien, modulo: 'auth' });
  const x = (await llamar('GET', '/api/logs/exportar')).archivo;
  assert.match(x.nombre, /^logs_auditoria_\d{8}_\d{4}\.xlsx$/);
  assert.equal(x.contenido.subarray(0, 2).toString(), 'PK');
  assert.ok(x.contenido.includes('xl/worksheets/sheet1.xml'));
  assert.ok(x.contenido.includes('Logs de auditoría'));
  const c = (await llamar('GET', '/api/logs/exportar?formato=csv')).archivo;
  assert.match(c.nombre, /\.csv$/);
  const texto = c.contenido.toString('utf8');
  assert.ok(texto.includes('Fecha y hora;Usuario;Operación;Tipo;Detalle;IP;Cambios (antes → después)'));
  assert.ok(texto.includes('Ingreso de admin.angular'));
});

test('exportar: una celda que empieza por = no queda como fórmula y los caracteres de control no rompen el Excel', async () => {
  bitacora.registrar('usuario_creado', '=HYPERLINK("x")\u0001', { quien });
  const texto = (await llamar('GET', '/api/logs/exportar?formato=csv')).archivo.contenido.toString('utf8');
  assert.ok(texto.includes(`"'=HYPERLINK(""x"")\u0001"`) || texto.includes(`'=HYPERLINK(""x"")`));
  const x = (await llamar('GET', '/api/logs/exportar')).archivo.contenido.toString('utf8');
  assert.ok(x.includes(`'=HYPERLINK(&quot;x&quot;)</t>`));
});

test('enviar: correo válido, dominio permitido, adjunto y registro en la bitácora con el correo enmascarado', async () => {
  assert.equal((await llamar('POST', '/api/logs/enviar', { para: 'sin-arroba' })).cuerpo.mensaje, 'Escriba un correo válido para enviar el reporte.');
  assert.equal((await llamar('POST', '/api/logs/enviar', { para: 'alguien@otro.co' })).cuerpo.mensaje, 'Los reportes solo se envían a correos de: angular.prueba.local.');
  const ok = await llamar('POST', '/api/logs/enviar', { para: 'Evaluador@Angular.Prueba.Local', formato: 'xlsx' });
  assert.deepEqual(ok.cuerpo, { mensaje: 'Reporte enviado a evaluador@angular.prueba.local.' });
  assert.equal(enviados[0].adjuntos[0].nombre.endsWith('.xlsx'), true);
  assert.match(estado.bitacora.at(-1).descripcion, /^Logs de auditoría enviados a e\*\*\*r@angular\.prueba\.local \(logs_auditoria_/);
  falla = 'Mailpit no responde';
  const mal = await llamar('POST', '/api/logs/enviar', { para: 'evaluador@angular.prueba.local' });
  assert.deepEqual(mal, { estado: 502, cuerpo: { mensaje: 'No se pudo enviar el correo: Mailpit no responde' } });
  assert.match(estado.bitacora.at(-1).descripcion, / · NO salió$/);
});
