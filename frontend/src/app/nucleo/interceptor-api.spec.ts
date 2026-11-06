import { HttpClient, provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CLAVE_ACTIVIDAD, CLAVE_MOTIVO } from './actividad';
import { ErrorApi, SIN_CONEXION } from './error-api';
import { interceptorApi, mismoOrigen } from './interceptor-api';
import { NAVEGADOR, RUTAS_BFF, Sesion } from './sesion';
import { proveerClientesApi } from './proveedores';

const asignar = vi.fn();
let http: HttpClient;
let ctrl: HttpTestingController;

function borrarCookie() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
}

beforeEach(() => {
  asignar.mockClear();
  localStorage.clear();
  sessionStorage.clear();
  borrarCookie();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([interceptorApi]), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
      provideHttpClientTesting(),
      ...proveerClientesApi(),
      { provide: NAVEGADOR, useValue: { asignar, recargar: vi.fn() } },
    ],
  });
  http = TestBed.inject(HttpClient);
  ctrl = TestBed.inject(HttpTestingController);
});
afterEach(() => {
  ctrl.verify();
  borrarCookie();
});

async function fallo(p: Promise<unknown>): Promise<ErrorApi> {
  try {
    await p;
  } catch (e) {
    return e as ErrorApi;
  }
  throw new Error('no falló');
}

const problema = (status: number, mensaje: string, extra: Record<string, unknown> = {}) => ({
  type: `urn:lume:error:${status}`,
  title: 'x',
  status,
  detail: mensaje,
  mensaje,
  ...extra,
});

describe('interceptorApi (como shared/api.ts del monolito)', () => {
  it('un error del servidor llega como ErrorApi con el mensaje, el estado y el cuerpo (motivo, campo)', async () => {
    const p = firstValueFrom(http.get('/api/usuarios'));
    ctrl.expectOne('/api/usuarios').flush(problema(422, 'Ese correo ya está registrado.', { campo: 'correo' }), {
      status: 422,
      statusText: 'x',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const e = await fallo(p);
    expect(e).toBeInstanceOf(ErrorApi);
    expect(e.estado).toBe(422);
    expect(e.message).toBe('Ese correo ya está registrado.');
    expect(e.campo).toBe('correo');
  });

  it('sin red: «No hay conexión…» con estado 0', async () => {
    const p = firstValueFrom(http.get('/api/usuarios'));
    ctrl.expectOne('/api/usuarios').error(new ProgressEvent('error'));
    const e = await fallo(p);
    expect(e.estado).toBe(0);
    expect(e.message).toBe(SIN_CONEXION);
  });

  it('413 sin mensaje: el texto del tamaño máximo; otro sin mensaje: «Error N»', async () => {
    const p1 = firstValueFrom(http.post('/api/archivos', {}));
    ctrl.expectOne('/api/auth/inicio').flush({});
    (await vi.waitFor(() => ctrl.expectOne('/api/archivos'))).flush('', { status: 413, statusText: 'x' });
    expect((await fallo(p1)).message).toContain('El envío supera el tamaño máximo permitido');
    const p2 = firstValueFrom(http.get('/api/x'));
    ctrl.expectOne('/api/x').flush('', { status: 500, statusText: 'x' });
    expect((await fallo(p2)).message).toBe('Error 500');
  });

  it('toda respuesta cuenta como actividad compartida, menos /api/sesion/estado (consultarla no renueva)', async () => {
    const p = firstValueFrom(http.get('/api/sesion/estado'));
    ctrl.expectOne('/api/sesion/estado').flush({ minutos: 10, restantes: 500 });
    await p;
    expect(localStorage.getItem(CLAVE_ACTIVIDAD)).toBeNull();
    const q = firstValueFrom(http.get('/api/sesion/mantener'));
    ctrl.expectOne('/api/sesion/mantener').flush({ minutos: 10 });
    await q;
    expect(Number(localStorage.getItem(CLAVE_ACTIVIDAD))).toBeGreaterThan(0);
  });

  it('a otro origen no se pide /api/auth/inicio ni va el token CSRF', async () => {
    document.cookie = 'XSRF-TOKEN=secreto; path=/';
    const p = firstValueFrom(http.post('https://otro.example/x', {}));
    const r = ctrl.expectOne('https://otro.example/x');
    expect(r.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    r.flush({});
    await p;
    borrarCookie();
    const q = firstValueFrom(http.post('https://otro.example/y', {}));
    ctrl.expectNone('/api/auth/inicio');
    ctrl.expectOne('https://otro.example/y').flush({});
    await q;
  });

  it('varias escrituras a la vez sin cookie comparten un solo /api/auth/inicio', async () => {
    const p = Promise.all([firstValueFrom(http.post('/api/a', {})), firstValueFrom(http.post('/api/b', {}))]);
    ctrl.expectOne('/api/auth/inicio').flush({});
    (await vi.waitFor(() => ctrl.expectOne('/api/a'))).flush({});
    (await vi.waitFor(() => ctrl.expectOne('/api/b'))).flush({});
    await p;
  });

  it('una escritura sin cookie XSRF pide antes /api/auth/inicio; con la cookie manda X-XSRF-TOKEN', async () => {
    const p = firstValueFrom(http.post('/api/cuenta/clave', { actual: 'a' }));
    const inicio = ctrl.expectOne('/api/auth/inicio');
    expect(inicio.request.method).toBe('GET');
    document.cookie = 'XSRF-TOKEN=abc%3D; path=/';
    inicio.flush({ captchaSitio: null, captchaDesactivado: true, csrf: 'X-XSRF-TOKEN' });
    const r = await vi.waitFor(() => ctrl.expectOne('/api/cuenta/clave'));
    expect(r.request.headers.get('X-XSRF-TOKEN')).toBe('abc=');
    r.flush({ mensaje: 'ok' });
    await p;
  });

  it('si la cookie CSRF aparece un instante después de la respuesta de inicio (WebKit), la escritura la espera y va con la cabecera', async () => {
    const p = firstValueFrom(http.post('/api/auth/clave/consultar', { tkn: 'x' }));
    ctrl.expectOne('/api/auth/inicio').flush({ captchaSitio: null, captchaDesactivado: true, csrf: 'X-XSRF-TOKEN' });
    setTimeout(() => (document.cookie = 'XSRF-TOKEN=tarde; path=/'), 60);
    const r = await vi.waitFor(() => ctrl.expectOne('/api/auth/clave/consultar'));
    expect(r.request.headers.get('X-XSRF-TOKEN')).toBe('tarde');
    r.flush({ valido: false });
    await p;
  });

  it('401 con motivo de cierre (no sin_sesion) fuera de /api/auth: guarda el motivo y va al ingreso por el gateway', async () => {
    const p = firstValueFrom(http.get('/api/usuarios'));
    const m = 'Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.';
    ctrl.expectOne('/api/usuarios').flush(problema(401, m, { motivo: 'reemplazada' }), { status: 401, statusText: 'x' });
    expect((await fallo(p)).motivo).toBe('reemplazada');
    expect(sessionStorage.getItem(CLAVE_MOTIVO)).toBe(m);
    expect(asignar).toHaveBeenCalledWith(RUTAS_BFF.autorizacion);
  });

  it('los 401 de /api/auth/* (credenciales, mfa_vencido) no cierran nada: los maneja la pantalla de ingreso', async () => {
    const p = firstValueFrom(http.post('/api/auth/codigo', { codigo: '1' }));
    ctrl.expectOne('/api/auth/inicio').flush({});
    (await vi.waitFor(() => ctrl.expectOne('/api/auth/codigo')))
      .flush(problema(401, 'La verificación venció.', { motivo: 'mfa_vencido' }), { status: 401, statusText: 'x' });
    await fallo(p);
    expect(sessionStorage.getItem(CLAVE_MOTIVO)).toBeNull();
    expect(asignar).not.toHaveBeenCalled();
  });

  it('401 sin_sesion con una sesión que existía (la del gateway venció): al ingreso con «La sesión expiró…»', async () => {
    const sesion = TestBed.inject(Sesion);
    const c = sesion.cargar();
    ctrl.expectOne('/api/sesion').flush({ id: 1, usuario: 'a', nombre: 'A', perfiles: [], menu: [], pantallas: [], minutosInactividad: 10 });
    await c;
    const p = firstValueFrom(http.get('/api/usuarios'));
    const m = 'La sesión expiró. Vuelva a iniciar sesión.';
    ctrl.expectOne('/api/usuarios').flush(problema(401, m, { motivo: 'sin_sesion' }), { status: 401, statusText: 'x' });
    await fallo(p);
    expect(sessionStorage.getItem(CLAVE_MOTIVO)).toBe(m);
    expect(asignar).toHaveBeenCalledWith(RUTAS_BFF.autorizacion);
  });

  it('401 sin_sesion sin sesión previa (abrir la app sin haber ingresado) no guarda motivo: lo decide la guarda', async () => {
    const p = firstValueFrom(http.get('/api/sesion'));
    ctrl.expectOne('/api/sesion').flush(problema(401, 'La sesión expiró. Vuelva a iniciar sesión.', { motivo: 'sin_sesion' }), {
      status: 401,
      statusText: 'x',
    });
    await fallo(p);
    expect(sessionStorage.getItem(CLAVE_MOTIVO)).toBeNull();
    expect(asignar).not.toHaveBeenCalled();
  });
});

describe('mismoOrigen', () => {
  const origen = 'http://localhost:4220';
  it('las rutas relativas sí; //otro, /\otro y las absolutas a otro origen no', () => {
    expect(mismoOrigen('/api/sesion', origen)).toBe(true);
    expect(mismoOrigen('http://localhost:4220/api/x', origen)).toBe(true);
    for (const u of ['//otro.example/x', '/\\otro.example/x', 'https://otro.example/x', 'http://localhost:9999/x']) {
      expect(mismoOrigen(u, origen), u).toBe(false);
    }
  });
});
