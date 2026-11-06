// Bitácora y /api/logs/** del contrato seguridad 0.5.0 (pantalla LOGS, solo Administrador), con las reglas y los textos del monolito
// (auditoria/ControladorLogs, reglas/Bitacora). Simplificación del mock: solo guarda los eventos de negocio (ingreso, salida, usuarios,
// roles, reportes), no una fila de «consulta» por cada petición como el monolito.
import { bogota } from './usuarios.mjs';
import { csv, excel, TIPO_CSV, TIPO_XLSX } from './xlsx.mjs';
import { permitidas } from './datos.mjs';

const POR_PAGINA = 100;
const MAX_DIAS_TEXTO = 92;
const IDENTIFICADOR = /^[a-z0-9_.-]{1,50}$/;
const CORREO = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const DIA = 86_400_000;
const OPERACIONES = ['insercion', 'actualizacion', 'eliminacion', 'consulta', 'ingreso', 'salida'];

/** Operación de cada tipo (reglas/Bitacora.TIPOS, solo los que registra el mock). */
const TIPOS = {
  login: 'ingreso',
  logout: 'salida',
  sesion_cerrada: 'salida',
  usuario_creado: 'insercion',
  usuario_editado: 'actualizacion',
  usuario_estado: 'actualizacion',
  usuario_restablecer: 'actualizacion',
  usuario_desbloqueado: 'actualizacion',
  perfil_editado: 'actualizacion',
  parametro_editado: 'actualizacion',
  rol_creado: 'insercion',
  rol_editado: 'actualizacion',
  rol_estado: 'actualizacion',
  reporte_enviado: 'consulta',
};

class Negocio extends Error {
  constructor(mensaje, estado = 422) {
    super(mensaje);
    this.estado = estado;
  }
}

/** El instante en hora de Bogotá con zona (ISO 8601), como `fechaLlave` y el cursor. */
const llave = (ms) => new Date(ms - 5 * 3_600_000).toISOString().replace('Z', '-05:00');
const diaBogota = (ms) => new Date(ms - 5 * 3_600_000).toISOString().slice(0, 10);
const fechaValida = (t) => (typeof t === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(t) && !Number.isNaN(Date.parse(t + 'T00:00:00Z')) ? t : null);
const sumarDias = (iso, n) => new Date(Date.parse(iso + 'T00:00:00Z') + n * DIA).toISOString().slice(0, 10);
const textoCambio = (v) => (v === null || v === undefined ? null : Array.isArray(v) ? `[${v.join(', ')}]` : String(v));

/** La bitácora en memoria: `registrar` y `registrarCambios` (antes y después; los datos personales solo constan como modificados). */
export function crearBitacora(estado) {
  const registrar = (tipo, descripcion, { quien = null, modulo = 'usuarios', cambios = null } = {}) => {
    const e = estado();
    e.bitacora.push({
      id: ++e.siguienteLog,
      ms: Date.now(),
      usuarioId: quien?.id ?? null,
      usuario: quien?.usuario ?? null,
      operacion: TIPOS[tipo] ?? 'consulta',
      tipo,
      modulo,
      descripcion,
      ip: '127.0.0.1',
      cambios,
    });
  };
  const registrarCambios = (tipo, descripcion, antes, despues, ocultos, opciones) => {
    const cambios = Object.keys({ ...antes, ...despues })
      .filter((k) => textoCambio(antes[k]) !== textoCambio(despues[k]))
      .map((k) => (ocultos.includes(k) ? { campo: k, antes: null, despues: null, oculto: true } : { campo: k, antes: textoCambio(antes[k]), despues: textoCambio(despues[k]), oculto: false }));
    registrar(tipo, descripcion, { ...opciones, cambios: cambios.length ? cambios : null });
  };
  return { registrar, registrarCambios };
}

/**
 * @param {object} c `estado()`, `filtroSesion`, `bitacora`, `enviarCorreo`, `enmascarar` y `dominios` (los permitidos para los reportes).
 */
export function crearRutasLogs(c) {
  const e = () => c.estado();

  function administrador(req, r) {
    const f = c.filtroSesion(req, r, true);
    if (!f) return null;
    if (!permitidas(f.u.perfiles, e().roles).some((p) => p.ruta === 'logs')) {
      r.problema(403, 'No tiene permiso para esta acción.');
      return null;
    }
    return f;
  }

  /** ControladorLogs.filtro: siempre por fechas (por omisión los últimos 7 días); invertidas se intercambian; texto ≤ 92 días. */
  function filtro(q) {
    const hoy = diaBogota(Date.now());
    let desde = fechaValida(q.desde) ?? sumarDias(hoy, -7);
    let hasta = fechaValida(q.hasta) ?? hoy;
    if (desde > hasta) [desde, hasta] = [hasta, desde];
    const texto = typeof q.texto === 'string' && q.texto.trim() ? q.texto.trim() : null;
    if (texto && (Date.parse(hasta) - Date.parse(desde)) / DIA > MAX_DIAS_TEXTO) throw new Negocio('Para buscar por texto, use un periodo de máximo 92 días.');
    const id = (v) => {
      if (v === null || v === undefined || v === '') return null;
      const t = String(v).trim().toLowerCase();
      if (!IDENTIFICADOR.test(t)) throw new Negocio('El tipo y el módulo son palabras sin espacios (p. ej. usuario_editado o usuarios).');
      return t;
    };
    const usuarioId = q.usuarioId === null || q.usuarioId === undefined || q.usuarioId === '' ? null : Number(q.usuarioId);
    const operacion = OPERACIONES.includes(q.operacion) ? q.operacion : null;
    return { desde, hasta, texto, modulo: id(q.modulo), tipo: id(q.tipo), usuarioId: Number.isInteger(usuarioId) ? usuarioId : null, operacion };
  }

  /** Las filas del filtro, de la más reciente a la más antigua. */
  function filas(f) {
    const t = f.texto?.toLowerCase();
    return e()
      .bitacora.filter((x) => {
        const dia = diaBogota(x.ms);
        return (
          dia >= f.desde &&
          dia <= f.hasta &&
          (f.usuarioId === null || x.usuarioId === f.usuarioId) &&
          (f.operacion === null || x.operacion === f.operacion) &&
          (f.modulo === null || x.modulo === f.modulo) &&
          (f.tipo === null || x.tipo === f.tipo) &&
          (!t || x.descripcion.toLowerCase().includes(t))
        );
      })
      .sort((a, b) => b.ms - a.ms || b.id - a.id);
  }

  const fila = (x) => ({
    id: x.id,
    fecha: bogota(x.ms) + new Date(x.ms - 5 * 3_600_000).toISOString().slice(16, 19),
    fechaLlave: llave(x.ms),
    usuario: x.usuario,
    operacion: x.operacion,
    tipo: x.tipo,
    modulo: x.modulo,
    servicio: 'seguridad',
    descripcion: x.descripcion,
    ip: x.ip,
    cambios: x.cambios,
  });

  const cambiosEnTexto = (cs) => (cs ?? []).map((x) => (x.oculto ? `${x.campo}: modificado` : `${x.campo}: ${x.antes ?? '—'} → ${x.despues ?? '—'}`)).join('; ');

  /** ControladorLogs.archivo: el mismo reporte para descargar y para enviar. */
  function archivo(f, formato, quien) {
    const filtros = { Periodo: `${f.desde} a ${f.hasta}` };
    if (f.usuarioId !== null) filtros['Usuario (id)'] = String(f.usuarioId);
    if (f.operacion) filtros['Operación'] = f.operacion;
    if (f.texto) filtros['Texto'] = f.texto;
    if (f.modulo) filtros['Módulo'] = f.modulo;
    if (f.tipo) filtros['Tipo'] = f.tipo;
    const ahora = Date.now();
    const reporte = {
      titulo: 'Logs de auditoría',
      generado: bogota(ahora),
      por: `${quien.nombre} (${quien.usuario})`,
      filtros,
      columnas: ['Fecha y hora', 'Usuario', 'Operación', 'Tipo', 'Detalle', 'IP', 'Cambios (antes → después)'],
      filas: filas(f).map((x) => {
        const v = fila(x);
        return [v.fecha, v.usuario ?? '', v.operacion, v.tipo, v.descripcion, v.ip ?? '', cambiosEnTexto(v.cambios)];
      }),
    };
    const base = 'logs_auditoria_' + bogota(ahora).replace(/[-:]/g, '').replace(' ', '_');
    return String(formato).toLowerCase() === 'csv'
      ? { nombre: base + '.csv', tipo: TIPO_CSV, contenido: csv(reporte) }
      : { nombre: base + '.xlsx', tipo: TIPO_XLSX, contenido: excel(reporte) };
  }

  const rutas = {
    'GET /api/logs/opciones': () => ({
      operaciones: OPERACIONES,
      usuarios: [...e().usuarios].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')).map((u) => ({ id: u.id, nombre: u.nombre, usuario: u.usuario })),
      modulos: [...new Set(e().bitacora.map((x) => x.modulo).filter(Boolean))].sort(),
      correoDefecto: process.env.DIGSA_MOCK_CORREO_REPORTES ?? '',
    }),
    'GET /api/logs': (_f, q) => {
      const f = filtro(q);
      let lista = filas(f);
      if (q.despuesFecha) {
        const df = Date.parse(q.despuesFecha);
        const di = Number(q.despuesId);
        if (Number.isNaN(df) || !Number.isInteger(di)) throw new Negocio('La página pedida no es válida.');
        lista = lista.filter((x) => x.ms < df || (x.ms === df && x.id < di));
      }
      const visibles = lista.slice(0, POR_PAGINA);
      const ultima = visibles.at(-1);
      return {
        filas: visibles.map(fila),
        siguiente: lista.length > POR_PAGINA ? { fecha: llave(ultima.ms), id: ultima.id } : null,
        desde: f.desde,
        hasta: f.hasta,
      };
    },
    'GET /api/logs/exportar': (f, q) => ({ archivo: archivo(filtro(q), q.formato ?? 'xlsx', f.u) }),
    'POST /api/logs/enviar': async (f, _q, b) => {
      const para = String(b.para ?? '').trim().toLowerCase();
      if (!CORREO.test(para) || para.length > 150) throw new Negocio('Escriba un correo válido para enviar el reporte.');
      const dominio = para.slice(para.indexOf('@') + 1);
      if (c.dominios.length && !c.dominios.some((d) => dominio === d || dominio.endsWith('.' + d))) {
        throw new Negocio(`Los reportes solo se envían a correos de: ${c.dominios.join(', ')}.`);
      }
      const a = archivo(filtro(b), b.formato ?? 'xlsx', f.u);
      let error = null;
      try {
        await c.enviarCorreo({ para, asunto: 'Logs de auditoría', texto: `Adjunto el reporte de logs de auditoría (${a.nombre}).`, adjuntos: [a] });
      } catch (x) {
        error = x.message;
      }
      c.bitacora.registrar('reporte_enviado', `Logs de auditoría enviados a ${c.enmascarar(para)} (${a.nombre})${error ? ' · NO salió' : ''}`, { quien: f.u, modulo: 'logs' });
      if (error) throw new Negocio('No se pudo enviar el correo: ' + error, 502);
      return { mensaje: `Reporte enviado a ${para}.` };
    },
  };

  return function rutaLogs(metodo, camino) {
    const manejar = rutas[`${metodo} ${camino}`];
    if (!manejar) return null;
    return async (req, r, cuerpo) => {
      const f = administrador(req, r);
      if (!f) return;
      try {
        const q = Object.fromEntries(new URL(req.url, 'http://x').searchParams);
        const res = await manejar(f, q, cuerpo);
        if (res.archivo) r.archivo(res.archivo);
        else r.json(res);
      } catch (err) {
        if (!(err instanceof Negocio)) throw err;
        r.problema(err.estado, err.message);
      }
    };
  };
}
