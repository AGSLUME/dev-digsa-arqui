// Mock del gateway + servicio `seguridad` para desarrollar y probar el frontend sin backend (ADR-007, ADR-014).
// Sigue contratos/seguridad.yaml (0.1.0) y el comportamiento del monolito (identidad/ y reglas/ de digsa-java). Sin dependencias: Node 24.
//
//   node mock/servidor.mjs            # 127.0.0.1:9200 (el puerto del gateway de la sesión angular, ADR-010)
//
// Variables: DIGSA_MOCK_PUERTO, DIGSA_MOCK_MINUTOS (inactividad, 10), DIGSA_MOCK_CODIGOS_POR_HORA (5), DIGSA_MOCK_URL_PUBLICA
// (http://localhost:4220, para los enlaces del correo), DIGSA_MOCK_TURNSTILE (clave de sitio; por omisión la de PRUEBA pública de
// Cloudflare que siempre aprueba; «no» = captcha desactivado). Los correos (código MFA, enlaces) van al Mailpit ya instalado.
//
// Solo para desarrollo y e2e: guarda todo en memoria, escucha solo en 127.0.0.1 y no tiene ningún secreto.
import { randomBytes, randomInt } from 'node:crypto';
import { createServer } from 'node:http';
import { enviarCorreo } from './correo.mjs';
import { permitidas, usuariosIniciales } from './datos.mjs';

const PUERTO = Number(process.env.DIGSA_MOCK_PUERTO ?? 9200);
const URL_PUBLICA = process.env.DIGSA_MOCK_URL_PUBLICA ?? 'http://localhost:4220';
const CODIGOS_POR_HORA = Number(process.env.DIGSA_MOCK_CODIGOS_POR_HORA ?? 5);
// Clave de sitio de PRUEBA documentada por Cloudflare (siempre aprueba): https://developers.cloudflare.com/turnstile/troubleshooting/testing/
const TURNSTILE = process.env.DIGSA_MOCK_TURNSTILE === 'no' ? null : (process.env.DIGSA_MOCK_TURNSTILE ?? '1x00000000000000000000AA');
const MIN = 60_000;

const GENERICO = 'Usuario o contraseña incorrectos. Después de 5 intentos fallidos el usuario se bloquea 15 minutos.';
const MFA_VENCIDO = 'La verificación venció. Ingrese de nuevo con su usuario y contraseña.';
const ENLACE_INVALIDO = 'El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».';
const CAPTCHA = 'Por favor complete la verificación de seguridad (captcha).';
const MOTIVOS = {
  sin_sesion: 'La sesión expiró. Vuelva a iniciar sesión.',
  inactividad: 'La sesión se cerró por inactividad. Vuelva a iniciar sesión.',
  inactivo: 'Su usuario está inactivo.',
  clave: 'La contraseña cambió. Inicie sesión de nuevo.',
  cerrada: 'Su sesión fue cerrada. Vuelva a iniciar sesión.',
  reemplazada: 'Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.',
  clave_vencida: 'Su contraseña venció. Ingrese de nuevo para cambiarla.',
  bloqueado: 'Su usuario está bloqueado 15 minutos por intentos fallidos. Vuelva a ingresar más tarde.',
};

// ---------- Estado (en memoria) ----------
let estado;
function reiniciar() {
  estado = {
    minutos: Number(process.env.DIGSA_MOCK_MINUTOS ?? 10),
    usuarios: usuariosIniciales(),
    /** Sesión del emisor y, después del canje, también del gateway: un mismo objeto (sid). */
    sesiones: new Map(),
    /** usuarioId → id de la sesión vigente (sesión única, decisión 7.1). */
    huellas: new Map(),
    codigos: new Map(),
    envios: new Map(),
    enlaces: new Map(),
    ultimoEnlace: new Map(),
  };
}
reiniciar();

const ahora = () => Date.now();
const token = (n = 32) => randomBytes(n).toString('hex');
const normal = (u) => String(u ?? '').trim().toLowerCase();
const enBlanco = (v) => typeof v !== 'string' || v.trim() === '';

/** Correo enmascarado como reglas/Correos.java: primera y última letra de la parte local, «*» entre ellas. */
function enmascarar(correo) {
  const [local, dominio] = correo.split('@');
  if (local.length <= 2) return `${local[0]}${local.length === 2 ? '*' : ''}@${dominio}`;
  return `${local[0]}${'*'.repeat(local.length - 2)}${local.at(-1)}@${dominio}`;
}

/** Política de claves de reglas/Claves.java, en su orden: el primer problema. */
function errorPolitica(c) {
  if (c == null) return 'La contraseña debe tener mínimo 12 caracteres.';
  if (/[ñÑáéíóúÁÉÍÓÚüÜ]/.test(c)) return 'La contraseña no puede tener ñ ni tildes.';
  if ([...c].length < 12) return 'La contraseña debe tener mínimo 12 caracteres.';
  if ([...c].length > 64 || Buffer.byteLength(c, 'utf8') > 72) return 'La contraseña debe tener máximo 64 caracteres.';
  if (!/[a-z]/.test(c) || !/[A-Z]/.test(c)) return 'La contraseña debe tener mayúsculas y minúsculas.';
  if (!/\d/.test(c)) return 'La contraseña debe tener al menos un número.';
  if (!/[^A-Za-z0-9]/.test(c)) return 'La contraseña debe tener al menos un carácter especial.';
  return null;
}

// ---------- HTTP ----------
function cookies(req) {
  return Object.fromEntries(
    (req.headers.cookie ?? '')
      .split(';')
      .map((c) => c.trim().split('='))
      .filter(([k]) => k)
      .map(([k, ...v]) => [k, decodeURIComponent(v.join('='))]),
  );
}
const cookie = (nombre, valor, { httpOnly = true, borrar = false } = {}) =>
  `${nombre}=${borrar ? '' : encodeURIComponent(valor)}; Path=/; SameSite=Strict${httpOnly ? '; HttpOnly' : ''}${borrar ? '; Max-Age=0' : ''}`;

class Respuesta {
  constructor(res) {
    this.res = res;
    this.galletas = [];
  }
  galleta(c) {
    this.galletas.push(c);
  }
  enviar(estadoHttp, cuerpo, tipo = 'application/json') {
    const cabeceras = { 'Cache-Control': 'no-store' };
    if (this.galletas.length) cabeceras['Set-Cookie'] = this.galletas;
    if (cuerpo === undefined) {
      this.res.writeHead(estadoHttp, cabeceras).end();
      return;
    }
    this.res.writeHead(estadoHttp, { ...cabeceras, 'Content-Type': `${tipo}; charset=utf-8` }).end(JSON.stringify(cuerpo));
  }
  json(cuerpo) {
    this.enviar(200, cuerpo);
  }
  /** Error RFC 7807 de comun.yaml, con `mensaje` (igual a `detail`), `campo` y `motivo`. */
  problema(estadoHttp, mensaje, extra = {}) {
    this.enviar(
      estadoHttp,
      { type: `urn:lume:error:${estadoHttp}`, title: TITULOS[estadoHttp] ?? 'Error', status: estadoHttp, detail: mensaje, mensaje, ...extra },
      'application/problem+json',
    );
  }
  redirigir(destino) {
    const cabeceras = { Location: destino, 'Cache-Control': 'no-store' };
    if (this.galletas.length) cabeceras['Set-Cookie'] = this.galletas;
    this.res.writeHead(302, cabeceras).end();
  }
}
const TITULOS = { 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 405: 'Method Not Allowed', 409: 'Conflict', 419: 'Page Expired', 422: 'Unprocessable Entity', 429: 'Too Many Requests', 502: 'Bad Gateway' };

async function leerCuerpo(req) {
  const partes = [];
  for await (const p of req) partes.push(p);
  if (partes.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(partes).toString('utf8'));
  } catch {
    return null;
  }
}

function sembrarCsrf(r) {
  r.galleta(cookie('XSRF-TOKEN', token(16), { httpOnly: false }));
}

// ---------- Sesiones ----------
function sesionDelEmisor(req, r, crear = false) {
  const s = estado.sesiones.get(cookies(req).MOCK_IDP);
  if (s && !s.cerrada) return s;
  if (!crear) return null;
  const nueva = { id: token(), usuarioId: null, autenticada: false, gateway: false, paso: null, ultimo: ahora(), cerrada: null, continuar: null };
  estado.sesiones.set(nueva.id, nueva);
  r.galleta(cookie('MOCK_IDP', nueva.id));
  return nueva;
}

const vencida = (s) => ahora() - s.ultimo >= estado.minutos * MIN;

/** ¿El usuario tiene otra sesión abierta y activa? (sesión única: se bloquea el segundo ingreso). */
function sesionAbierta(u) {
  const s = estado.sesiones.get(estado.huellas.get(u.id));
  return Boolean(s && s.gateway && !s.cerrada && !vencida(s));
}

function cerrar(s, motivo) {
  s.cerrada = motivo;
  s.autenticada = false;
  if (estado.huellas.get(s.usuarioId) === s.id) estado.huellas.delete(s.usuarioId);
}

const usuarioDe = (id) => estado.usuarios.find((u) => u.id === id);
const bloqueado = (u) => u.bloqueadoHasta > ahora();

function registrarFallo(u) {
  u.intentos += 1;
  if (u.intentos >= 5) {
    u.bloqueadoHasta = ahora() + 15 * MIN;
    u.intentos = 0;
  }
}

async function enviarCodigo(u, r) {
  const hora = (estado.envios.get(u.id) ?? []).filter((t) => ahora() - t < 60 * MIN);
  if (hora.length >= CODIGOS_POR_HORA) {
    r.problema(429, `Ya se enviaron ${CODIGOS_POR_HORA} códigos en la última hora. Espere un momento antes de pedir otro.`);
    return false;
  }
  const codigo = String(randomInt(0, 1_000_000)).padStart(6, '0');
  try {
    await enviarCorreo({
      para: u.correo,
      asunto: 'Código de verificación',
      texto: `Hola ${u.nombre}.\n\nSu código de verificación para ingresar a LUME es: ${codigo}\n\nVence en 15 minutos. Si usted no intentó ingresar, avise al administrador.`,
    });
  } catch {
    r.problema(502, 'No se pudo enviar el código a su correo. Intente de nuevo o avise al administrador.');
    return false;
  }
  estado.envios.set(u.id, [...hora, ahora()]);
  estado.codigos.set(u.id, { codigo, vence: ahora() + 15 * MIN, intentos: 0, usado: false });
  return true;
}

async function enviarEnlace(u, asunto) {
  const tkn = token(32);
  for (const [k, e] of estado.enlaces) if (e.usuarioId === u.id) estado.enlaces.delete(k);
  estado.enlaces.set(tkn, { usuarioId: u.id, vence: ahora() + 60 * MIN, usado: false });
  estado.ultimoEnlace.set(u.id, ahora());
  await enviarCorreo({
    para: u.correo,
    asunto,
    texto: `Hola ${u.nombre}.\n\nPara definir su contraseña abra este enlace (vence en 60 minutos y sirve una sola vez):\n${URL_PUBLICA}/definir-clave?tkn=${tkn}`,
  });
}

function enlaceVigente(tkn) {
  if (typeof tkn !== 'string' || !/^[a-f0-9]{64}$/.test(tkn)) return null;
  const e = estado.enlaces.get(tkn);
  if (!e || e.usado || e.vence < ahora()) return null;
  const u = usuarioDe(e.usuarioId);
  return u && u.activo ? { e, u } : null;
}

const pasoVigente = (s, tipo) => s && s.paso && s.paso.tipo === tipo && ahora() - s.paso.desde <= 15 * MIN;

/** El filtro de sesión del monolito (FiltroSesion) para /api/** con sesión: el motivo de cada 401, en su orden. */
function filtroSesion(req, r, renovar) {
  const s = estado.sesiones.get(cookies(req).LUME_SESION);
  if (!s || !s.gateway) return r.problema(401, MOTIVOS.sin_sesion, { motivo: 'sin_sesion' });
  const cierre = (m) => {
    cerrar(s, m);
    r.galleta(cookie('LUME_SESION', '', { borrar: true }));
    estado.sesiones.delete(s.id);
    r.problema(401, MOTIVOS[m], { motivo: m });
  };
  if (s.cerrada) return cierre(s.cerrada);
  if (vencida(s)) return cierre('inactividad');
  const u = usuarioDe(s.usuarioId);
  if (!u || !u.activo) return cierre('inactivo');
  if (estado.huellas.get(u.id) !== s.id) return cierre('reemplazada');
  if (renovar) s.ultimo = ahora();
  return { s, u };
}

function datosSesion(u) {
  const p = permitidas(u.perfiles);
  return {
    id: u.id,
    usuario: u.usuario,
    nombre: u.nombre,
    perfiles: [...u.perfiles].sort(),
    menu: p.filter(([, , , , oculta]) => !oculta).map(([ruta, titulo, grupo]) => ({ ruta, titulo, grupo })),
    pantallas: p.map(([ruta]) => ruta),
    minutosInactividad: estado.minutos,
  };
}

// ---------- Rutas ----------
const rutas = {
  // --- Gateway (BFF) y emisor OIDC simulados (ADR-014) ---
  'GET /bff/oauth2/authorization/lume': (req, r) => {
    const s = sesionDelEmisor(req, r, true);
    s.continuar = `/oauth2/authorize?response_type=code&client_id=gateway&state=${token(8)}`;
    r.redirigir(s.continuar);
  },
  'GET /oauth2/authorize': (req, r) => {
    const s = sesionDelEmisor(req, r, true);
    if (!s.autenticada) {
      s.continuar = req.url;
      return r.redirigir('/ingreso');
    }
    r.redirigir(`/bff/login/oauth2/code/lume?code=${token(8)}`);
  },
  'GET /bff/login/oauth2/code/lume': (req, r) => {
    const s = sesionDelEmisor(req, r);
    if (!s || !s.autenticada) return r.redirigir('/bff/oauth2/authorization/lume');
    s.gateway = true;
    s.ultimo = ahora();
    estado.huellas.set(s.usuarioId, s.id);
    r.galleta(cookie('LUME_SESION', s.id));
    sembrarCsrf(r);
    r.redirigir('/');
  },
  'POST /bff/salir': (req, r) => {
    const c = cookies(req);
    for (const id of [c.LUME_SESION, c.MOCK_IDP]) {
      const s = estado.sesiones.get(id);
      if (s) {
        cerrar(s, 'cerrada');
        estado.sesiones.delete(id);
      }
    }
    r.galleta(cookie('LUME_SESION', '', { borrar: true }));
    r.galleta(cookie('MOCK_IDP', '', { borrar: true }));
    r.enviar(204);
  },

  // --- Ingreso (/api/auth/*, públicas) ---
  'GET /api/auth/inicio': (req, r) => {
    if (!cookies(req)['XSRF-TOKEN']) sembrarCsrf(r);
    r.json({ captchaSitio: TURNSTILE, captchaDesactivado: TURNSTILE === null, csrf: 'X-XSRF-TOKEN' });
  },
  'POST /api/auth/ingreso': async (req, r, b) => {
    if (enBlanco(b.usuario)) return r.problema(422, 'Escriba su usuario.', { campo: 'usuario' });
    if (enBlanco(b.clave)) return r.problema(422, 'Escriba su contraseña.', { campo: 'clave' });
    if (TURNSTILE !== null && enBlanco(b.captcha)) return r.problema(422, CAPTCHA);
    const s = sesionDelEmisor(req, r, true);
    const u = estado.usuarios.find((x) => x.usuario === normal(b.usuario));
    if (!u || !u.activo || bloqueado(u)) return r.problema(401, GENERICO);
    if (u.clave !== b.clave) {
      registrarFallo(u);
      return r.problema(401, GENERICO);
    }
    u.intentos = 0;
    if (sesionAbierta(u)) {
      return r.problema(
        409,
        `Su usuario ya tiene una sesión abierta en otro equipo o navegador. Ciérrela con «Salir», espere ${estado.minutos} minutos sin actividad o pida al administrador que la cierre. Solo se permite una sesión a la vez.`,
        { motivo: 'sesion_abierta' },
      );
    }
    const correo = enmascarar(u.correo);
    if (u.claveInicial) {
      s.paso = { tipo: 'cambio', usuarioId: u.id, desde: ahora() };
      return r.json({ paso: 'cambiar_clave', mensaje: 'Es su primer ingreso con la contraseña que le asignó el administrador: defina una contraseña nueva.', correo, enlaceLocal: null });
    }
    if (u.definir) {
      let salio = true;
      await enviarEnlace(u, 'Defina su contraseña').catch(() => (salio = false));
      const fin = salio ? ' Le enviamos un enlace a su correo.' : ' No se pudo enviar el correo: avise al administrador.';
      return r.json({ paso: 'definir_clave', mensaje: 'Es su primer ingreso: debe definir su contraseña.' + fin, correo, enlaceLocal: null });
    }
    if (!(await enviarCodigo(u, r))) return;
    s.paso = { tipo: 'mfa', usuarioId: u.id, desde: ahora() };
    r.json({ paso: 'codigo', mensaje: 'Le enviamos un código de 6 dígitos a su correo.', correo, enlaceLocal: null });
  },
  'POST /api/auth/codigo': (req, r, b) => {
    if (enBlanco(b.codigo)) return r.problema(422, 'Escriba el código.', { campo: 'codigo' });
    const s = sesionDelEmisor(req, r);
    if (!pasoVigente(s, 'mfa')) return r.problema(401, MFA_VENCIDO, { motivo: 'mfa_vencido' });
    const u = usuarioDe(s.paso.usuarioId);
    if (!u || !u.activo || bloqueado(u)) return r.problema(401, GENERICO);
    if (sesionAbierta(u)) return r.problema(409, 'Su usuario ya tiene una sesión abierta en otro equipo o navegador.', { motivo: 'sesion_abierta' });
    const c = estado.codigos.get(u.id);
    if (!c) return r.problema(422, 'No hay un código vigente. Pida un código nuevo.');
    const agotado = 'El código venció o superó los intentos. Pida un código nuevo.';
    if (c.usado || c.vence < ahora() || c.intentos >= 5) return r.problema(422, agotado);
    if (c.codigo !== b.codigo.trim()) {
      c.intentos += 1;
      return r.problema(422, c.intentos >= 5 ? agotado : 'El código no es correcto. Revise el último correo que le enviamos.');
    }
    c.usado = true;
    s.usuarioId = u.id;
    s.autenticada = true;
    s.paso = null;
    sembrarCsrf(r);
    r.json({ usuario: u.usuario, nombre: u.nombre, continuar: s.continuar ?? '/bff/oauth2/authorization/lume' });
  },
  'POST /api/auth/codigo/reenviar': async (req, r) => {
    const s = sesionDelEmisor(req, r);
    if (!pasoVigente(s, 'mfa')) return r.problema(401, MFA_VENCIDO, { motivo: 'mfa_vencido' });
    const u = usuarioDe(s.paso.usuarioId);
    if (!u || !u.activo) return r.problema(401, GENERICO);
    if (!(await enviarCodigo(u, r))) return;
    r.json({ paso: 'codigo', mensaje: 'Le enviamos un código nuevo.', correo: enmascarar(u.correo), enlaceLocal: null });
  },
  'POST /api/auth/cambiar-clave': async (req, r, b) => {
    const s = sesionDelEmisor(req, r);
    if (!pasoVigente(s, 'cambio')) {
      const u = s?.paso ? usuarioDe(s.paso.usuarioId) : null;
      if (u && !u.claveInicial) return r.problema(409, 'La contraseña ya se cambió (¿envió el formulario dos veces?). Ingrese con la contraseña nueva.');
      return r.problema(401, MFA_VENCIDO, { motivo: 'mfa_vencido' });
    }
    const u = usuarioDe(s.paso.usuarioId);
    if (!u || !u.activo) return r.problema(401, GENERICO);
    if (enBlanco(b.clave)) return r.problema(422, 'Escriba la contraseña.', { campo: 'clave' });
    const politica = errorPolitica(b.clave);
    if (politica) return r.problema(422, politica);
    if (b.confirmacion != null && b.confirmacion !== b.clave) return r.problema(422, 'Las contraseñas no coinciden.');
    if (b.clave === u.clave) return r.problema(422, 'La nueva contraseña debe ser diferente a la que le asignó el administrador.');
    u.clave = b.clave;
    u.claveInicial = false;
    if (!(await enviarCodigo(u, r))) return;
    s.paso = { tipo: 'mfa', usuarioId: u.id, desde: ahora() };
    r.json({ paso: 'codigo', mensaje: 'Contraseña actualizada. Le enviamos un código de 6 dígitos a su correo.', correo: enmascarar(u.correo), enlaceLocal: null });
  },
  'POST /api/auth/olvide': (req, r, b) => {
    if (enBlanco(b.valor)) return r.problema(422, 'Escriba su usuario o su correo.', { campo: 'valor' });
    if (TURNSTILE !== null && enBlanco(b.captcha)) return r.problema(422, CAPTCHA);
    // Siempre la misma respuesta (anti-enumeración); el envío va en segundo plano, como uno cada 5 minutos por usuario
    const v = normal(b.valor);
    const u = estado.usuarios.find((x) => x.activo && (x.usuario === v || x.correo === v));
    if (u && ahora() - (estado.ultimoEnlace.get(u.id) ?? 0) >= 5 * MIN) void enviarEnlace(u, 'Recuperación de contraseña').catch(() => undefined);
    r.json({ mensaje: 'Si el usuario o el correo están registrados y activos, le enviamos un enlace para definir una contraseña nueva.' });
  },
  'GET /api/auth/clave': (req, r) => {
    const tkn = new URL(req.url, 'http://x').searchParams.get('tkn');
    if (tkn === null) return r.problema(400, 'La petición no tiene el formato esperado.');
    const v = enlaceVigente(tkn);
    r.json(v ? { valido: true, nombre: v.u.nombre } : { valido: false, mensaje: ENLACE_INVALIDO });
  },
  'POST /api/auth/clave': (req, r, b) => {
    const v = enlaceVigente(b.tkn);
    if (!v) return r.problema(422, ENLACE_INVALIDO);
    if (enBlanco(b.clave)) return r.problema(422, 'Escriba la contraseña.', { campo: 'clave' });
    const politica = errorPolitica(b.clave);
    if (politica) return r.problema(422, politica);
    if (b.confirmacion != null && b.confirmacion !== b.clave) return r.problema(422, 'Las contraseñas no coinciden.');
    if (v.u.clave !== null && b.clave === v.u.clave) return r.problema(422, 'La nueva contraseña debe ser diferente a la actual.');
    v.e.usado = true;
    Object.assign(v.u, { clave: b.clave, claveInicial: false, definir: false, intentos: 0, bloqueadoHasta: 0 });
    const abierta = estado.sesiones.get(estado.huellas.get(v.u.id));
    if (abierta) cerrar(abierta, 'clave');
    r.json({ mensaje: 'La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña.' });
  },

  // --- Sesión y cuenta (con sesión) ---
  'GET /api/sesion': (req, r) => {
    const f = filtroSesion(req, r, true);
    if (f) r.json(datosSesion(f.u));
  },
  'GET /api/sesion/estado': (req, r) => {
    const f = filtroSesion(req, r, false); // consultar el estado no renueva
    if (f) r.json({ minutos: estado.minutos, restantes: Math.max(0, estado.minutos * 60 - Math.floor((ahora() - f.s.ultimo) / 1000)) });
  },
  'GET /api/sesion/mantener': (req, r) => {
    const f = filtroSesion(req, r, true);
    if (f) r.json({ minutos: estado.minutos });
  },
  'POST /api/cuenta/clave': (req, r, b) => {
    const f = filtroSesion(req, r, true);
    if (!f) return;
    const { s, u } = f;
    if (enBlanco(b.actual)) return r.problema(422, 'Escriba su contraseña actual.', { campo: 'actual' });
    if (enBlanco(b.clave)) return r.problema(422, 'Escriba la contraseña nueva.', { campo: 'clave' });
    const bloquear = () => {
      cerrar(s, 'bloqueado');
      estado.sesiones.delete(s.id);
      r.galleta(cookie('LUME_SESION', '', { borrar: true }));
      r.problema(401, MOTIVOS.bloqueado, { motivo: 'bloqueado' });
    };
    if (bloqueado(u)) return bloquear();
    if (b.actual !== u.clave) {
      registrarFallo(u);
      return bloqueado(u) ? bloquear() : r.problema(422, 'La contraseña actual no es correcta.');
    }
    const politica = errorPolitica(b.clave);
    if (politica) return r.problema(422, politica);
    if (b.confirmacion != null && b.confirmacion !== b.clave) return r.problema(422, 'Las contraseñas no coinciden.');
    if (b.clave === u.clave) return r.problema(422, 'La nueva contraseña debe ser diferente a la actual.');
    u.clave = b.clave;
    u.intentos = 0;
    sembrarCsrf(r);
    r.json({ mensaje: 'Su contraseña se cambió. La próxima vez ingrese con la contraseña nueva.' });
  },

  // --- Control del mock para las e2e (solo 127.0.0.1) ---
  'POST /__mock/reiniciar': (_req, r) => {
    reiniciar();
    r.enviar(204);
  },
  'POST /__mock/minutos': (_req, r, b) => {
    estado.minutos = Number(b.minutos) || estado.minutos;
    r.json({ minutos: estado.minutos });
  },
  /**
   * Crea un usuario de prueba (lo que en el monolito hace POST /api/usuarios, que llega con el contrato de usuarios). Con `claveInicial`
   * el primer ingreso pide cambiarla. El correo es siempre @angular.prueba.local.
   */
  'POST /__mock/usuarios': (_req, r, b) => {
    const usuario = normal(b.usuario);
    if (!usuario || estado.usuarios.some((u) => u.usuario === usuario)) return r.problema(422, 'Ese usuario ya existe.', { campo: 'usuario' });
    const id = Math.max(...estado.usuarios.map((u) => u.id)) + 1;
    const nuevo = {
      id,
      usuario,
      nombre: String(b.nombre ?? 'Usuario de Prueba'),
      perfiles: Array.isArray(b.perfiles) && b.perfiles.length ? b.perfiles.map(String) : ['Auditor'],
      clave: String(b.claveInicial ?? b.clave ?? ''),
      claveInicial: b.claveInicial != null,
      correo: `${usuario}@angular.prueba.local`,
      activo: true,
      intentos: 0,
      bloqueadoHasta: 0,
    };
    estado.usuarios.push(nuevo);
    r.enviar(201, { id, usuario, correo: nuevo.correo });
  },
  /** Cierra la sesión de un usuario como lo haría el administrador o el servidor (motivo de 401). */
  'POST /__mock/cerrar': (_req, r, b) => {
    const u = estado.usuarios.find((x) => x.usuario === normal(b.usuario));
    const s = u && estado.sesiones.get(estado.huellas.get(u.id));
    if (s) cerrar(s, b.motivo ?? 'cerrada');
    r.enviar(s ? 204 : 404);
  },
};

const ESCRITURA = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Contra DNS rebinding: solo se atiende con Host local (directo o por el proxy de ng serve, que lo conserva). */
export function hostLocal(host) {
  const nombre = String(host ?? '')
    .replace(/:\d+$/, '')
    .replace(/^\[(.*)\]$/, '$1')
    .toLowerCase();
  return ['127.0.0.1', 'localhost', '::1'].includes(nombre);
}

const servidor = createServer(async (req, res) => {
  const r = new Respuesta(res);
  if (!hostLocal(req.headers.host)) return r.problema(403, 'No tiene permiso para esta acción.');
  const camino = new URL(req.url, 'http://x').pathname;
  try {
    const manejador = rutas[`${req.method} ${camino}`];
    if (!manejador) {
      if (camino === '/api/sesion/mantener') return r.problema(405, 'Método no permitido.');
      return r.problema(404, 'No existe.');
    }
    // Control del mock: solo con la cabecera propia (una página de otro origen no puede mandarla sin preflight, que aquí no se acepta)
    if (camino.startsWith('/__mock/') && req.headers['x-mock-control'] !== '1') return r.problema(403, 'No tiene permiso para esta acción.');
    let cuerpo = {};
    if (ESCRITURA.has(req.method)) {
      // CSRF del gateway: la cabecera debe repetir la cookie (sin ella, 419)
      const c = cookies(req)['XSRF-TOKEN'];
      if (!camino.startsWith('/__mock/') && (!c || req.headers['x-xsrf-token'] !== c)) {
        return r.problema(419, 'La página expiró. Recárguela e intente de nuevo.');
      }
      cuerpo = await leerCuerpo(req);
      if (cuerpo === null) return r.problema(400, 'La petición no tiene el formato esperado.');
    }
    await manejador(req, r, cuerpo);
  } catch (e) {
    console.error(e);
    if (!res.headersSent) r.problema(500, 'Ocurrió un error en el servidor. Intente de nuevo o avise al administrador.');
  }
});

servidor.listen(PUERTO, '127.0.0.1', () => {
  console.log(`Mock de seguridad (gateway + seguridad) en http://127.0.0.1:${PUERTO} · inactividad ${estado.minutos} min`);
});
