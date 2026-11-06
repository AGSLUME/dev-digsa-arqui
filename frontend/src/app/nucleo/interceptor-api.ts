import { HttpErrorResponse, HttpEventType, HttpInterceptorFn, HttpRequest, HttpXsrfTokenExtractor } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, defer, from, switchMap, tap, throwError } from 'rxjs';
import { guardarMotivo, marcarActividad } from './actividad';
import { ErrorApi, SIN_CONEXION } from './error-api';
import { Sesion } from './sesion';

/**
 * Cliente HTTP de la SPA (ADR-003, ADR-011, ADR-014): el equivalente de frontend/src/shared/api.ts del monolito para el HttpClient de
 * Angular. NO hay token en el navegador: la cookie HttpOnly del BFF viaja sola y el CSRF va en `X-XSRF-TOKEN` (lo pone
 * `withXsrfConfiguration` con la cookie `XSRF-TOKEN`). Este interceptor:
 * - antes de una escritura sin cookie `XSRF-TOKEN`, pide `GET /api/auth/inicio`, que la deja lista (como `asegurarCsrf`);
 * - marca la actividad compartida entre pestañas con cada respuesta del servidor, salvo `/api/sesion/estado` (no renueva la sesión);
 * - convierte todo error en `ErrorApi` con el `mensaje` del servidor (los Problema RFC 7807 lo traen), «No hay conexión…» sin red, el
 *   texto del tamaño máximo en un 413 sin mensaje y «Error N» en lo demás. El 403 y el 419 llegan con su mensaje a la pantalla, como en
 *   el monolito («No tiene permiso para esta acción.», «La página expiró. Recárguela e intente de nuevo.»);
 * - un 401 con `motivo` de cierre (inactividad, otra sesión, cerrada por el administrador...) fuera de `/api/auth/*` guarda el mensaje
 *   para el ingreso y va al ingreso por el gateway. Un 401 `sin_sesion` hace lo mismo solo si había una sesión (la del gateway venció);
 *   sin sesión previa lo resuelve la guarda de rutas.
 */

const INICIO = '/api/auth/inicio';
const ESTADO = '/api/sesion/estado';
const DEMASIADO_GRANDE =
  'El envío supera el tamaño máximo permitido (25 MB por archivo, 100 MB los videos). Envíe menos archivos o más livianos.';

/** Rutas que no usan la sesión del usuario: su 401 no cierra la sesión. */
function sinSesionDeUsuario(url: string): boolean {
  return url.startsWith('/api/auth/') || url.startsWith('/api/opl-publico/');
}

/** ¿La petición va al mismo origen de la página? Se resuelve como lo haría el navegador. */
export function mismoOrigen(url: string, origen = globalThis.location?.origin ?? 'http://localhost'): boolean {
  try {
    return new URL(url, origen).origin === origen;
  } catch {
    return false;
  }
}

function esEscritura(req: HttpRequest<unknown>): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes(req.method);
}

export function aErrorApi(e: HttpErrorResponse): ErrorApi {
  if (e.status === 0) return new ErrorApi(0, SIN_CONEXION);
  const datos: Record<string, unknown> = e.error !== null && typeof e.error === 'object' && !(e.error instanceof Blob) ? e.error : {};
  const m = datos['mensaje'] ?? datos['detail'];
  const mensaje = typeof m === 'string' ? m : e.status === 413 ? DEMASIADO_GRANDE : `Error ${e.status}`;
  return new ErrorApi(e.status, mensaje, datos);
}

/**
 * El cuerpo de error de una descarga (`responseType: 'blob'`) llega como Blob: se lee como JSON (el Problema, con su `mensaje` y su
 * `motivo`), para que el 422 o el 401 de una descarga se traten como los demás. Si no es JSON, el error queda como estaba.
 */
export async function legible(e: HttpErrorResponse): Promise<HttpErrorResponse> {
  if (!(e.error instanceof Blob)) return e;
  try {
    const error: unknown = JSON.parse(await e.error.text());
    return new HttpErrorResponse({ error, headers: e.headers, status: e.status, statusText: e.statusText, url: e.url ?? undefined });
  } catch {
    return e;
  }
}

/** Un solo GET /api/auth/inicio a la vez: varias escrituras simultáneas sin cookie CSRF lo comparten. */
@Injectable({ providedIn: 'root' })
export class InicioCsrf {
  enCurso: Promise<void> | null = null;
}

export const interceptorApi: HttpInterceptorFn = (req, next) => {
  const sesion = inject(Sesion);
  const inicio = inject(InicioCsrf);
  const xsrf = inject(HttpXsrfTokenExtractor);
  const url = req.url;

  const enviar = (peticion: HttpRequest<unknown>) =>
    next(peticion).pipe(
      tap((ev) => {
        if (ev.type === HttpEventType.Response && url !== ESTADO) marcarActividad();
      }),
      // Primero el cuerpo de error de una descarga (Blob) se vuelve el Problema que es; luego, el manejo de siempre
      catchError((x: unknown) => (x instanceof HttpErrorResponse ? from(legible(x)).pipe(switchMap((e) => throwError(() => e))) : throwError(() => x))),
      catchError((e: unknown) => {
        if (!(e instanceof HttpErrorResponse)) return throwError(() => e);
        if (e.status !== 0 && url !== ESTADO) marcarActividad();
        const error = aErrorApi(e);
        if (error.estado === 401 && !sinSesionDeUsuario(url)) {
          const motivo = error.motivo;
          const cerrada = motivo !== undefined && (motivo !== 'sin_sesion' || sesion.datos() !== null);
          if (cerrada) {
            guardarMotivo(error.message);
            sesion.irAlIngreso();
          }
        }
        return throwError(() => error);
      }),
    );

  // Solo el mismo origen: a otro origen (también «//otro» o «/\otro», que el navegador resuelve afuera) ni se pide /api/auth/inicio ni
  // va el token CSRF
  if (!mismoOrigen(url) || url === INICIO || !esEscritura(req) || xsrf.getToken() !== null) return enviar(req);
  // Sin cookie CSRF todavía: GET /api/auth/inicio la deja lista y luego va la escritura. El interceptor XSRF de Angular corre antes que
  // este (ya pasó, sin cookie), así que la cabecera se pone aquí con el valor recién recibido.
  // Varias escrituras a la vez sin cookie comparten un solo GET /api/auth/inicio
  // WebKit deja la cookie en document.cookie un instante DESPUÉS de terminar la respuesta: sin esperarla, la primera escritura sale sin
  // la cabecera y el gateway responde 419 (pasaba en «Definir contraseña», cuya primera petición es un POST). Se espera hasta 500 ms.
  const esperarCookie = async () => {
    for (let i = 0; i < ESPERAS_COOKIE && xsrf.getToken() === null; i++) await new Promise((r) => setTimeout(r, 20));
  };
  const pedirInicio = () =>
    (inicio.enCurso ??= new Promise<void>((listo) => next(new HttpRequest('GET', INICIO)).subscribe({ complete: listo, error: () => listo() }))
      .then(esperarCookie)
      .finally(() => (inicio.enCurso = null)));
  return defer(() => from(pedirInicio())).pipe(
    switchMap(() => {
      const token = xsrf.getToken();
      return enviar(token !== null && !req.headers.has(CABECERA_XSRF) ? req.clone({ setHeaders: { [CABECERA_XSRF]: token } }) : req);
    }),
  );
};

const CABECERA_XSRF = 'X-XSRF-TOKEN';
/** 25 esperas de 20 ms: hasta 500 ms a que la cookie CSRF aparezca tras /api/auth/inicio. */
const ESPERAS_COOKIE = 25;
