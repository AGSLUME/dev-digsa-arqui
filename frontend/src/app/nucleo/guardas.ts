import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { ErrorApi } from './error-api';
import { Sesion } from './sesion';

/**
 * Guardas de rutas (ADR-011, ADR-014). Las dos son `canMatch`, que el router evalúa de la ruta padre a las hijas: así la sesión ya está
 * cargada cuando cada pantalla pregunta si el perfil la tiene (también en una carga directa, F5 en /usuarios).
 * - `conSesion` (en la ruta del shell): carga la sesión una vez. Sin sesión (401) va al ingreso por el gateway con página completa y la
 *   ruta no coincide (la ruta final vacía cubre el instante en que la página cambia); sin red u otro error, a «No hay conexión con el
 *   servidor» con «Reintentar».
 * - `pantalla('usuarios')`: la ruta solo existe para quien tiene esa pantalla (lo decide el servidor, Anexo 5 num. 3.7). Sin permiso la
 *   ruta no coincide y cae en el comodín del shell, que muestra «Acceso restringido.» como `Protegida` del monolito.
 */
export const conSesion: CanMatchFn = async () => {
  const sesion = inject(Sesion);
  const router = inject(Router);
  const destino = router.currentNavigation()?.initialUrl.toString() ?? router.url;
  try {
    await sesion.cargar();
    return true;
  } catch (e) {
    if (e instanceof ErrorApi && e.estado === 401) {
      sesion.irAlIngreso();
      return false;
    }
    return router.createUrlTree(['/sin-conexion'], { queryParams: { volver: destino } });
  }
};

export function pantalla(ruta: string): CanMatchFn {
  return () => inject(Sesion).puedeEntrar(ruta);
}
