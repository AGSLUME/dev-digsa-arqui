/**
 * Marcas que la SPA comparte entre pestañas y con la pantalla de ingreso, con las mismas claves que el monolito (shared/api.ts):
 * - `digsa.actividad` (localStorage): la última respuesta del servidor, para que el aviso de inactividad de otra pestaña se cierre;
 * - `digsa.motivo` (sessionStorage): por qué se cerró la sesión; el ingreso lo muestra una vez en la alerta `motivo-cierre`.
 * Sin almacenamiento (ventana privada, bloqueado) nada falla: la pestaña sigue funcionando sola.
 */
export const CLAVE_ACTIVIDAD = 'digsa.actividad';
export const CLAVE_MOTIVO = 'digsa.motivo';

export function marcarActividad(ahora = Date.now()): void {
  try {
    localStorage.setItem(CLAVE_ACTIVIDAD, String(ahora));
  } catch {
    // sin almacenamiento: el aviso de esta pestaña sigue funcionando
  }
}

/** La última actividad que dejó cualquier pestaña (0 si no hay). */
export function ultimaActividadCompartida(): number {
  try {
    return Number(localStorage.getItem(CLAVE_ACTIVIDAD) ?? 0) || 0;
  } catch {
    return 0;
  }
}

export function guardarMotivo(mensaje: string): void {
  try {
    sessionStorage.setItem(CLAVE_MOTIVO, mensaje);
  } catch {
    // sin almacenamiento
  }
}

/** El motivo guardado, que se borra al leerlo (se muestra una sola vez). */
export function tomarMotivo(): string | null {
  try {
    const m = sessionStorage.getItem(CLAVE_MOTIVO);
    if (m !== null) sessionStorage.removeItem(CLAVE_MOTIVO);
    return m;
  } catch {
    return null;
  }
}
