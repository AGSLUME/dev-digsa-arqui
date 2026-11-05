/**
 * Validación de direcciones a las que la SPA navega con un valor que viene de afuera (la consulta `?volver=`, el `continuar` del servidor):
 * solo rutas del mismo origen. Rechaza lo que el navegador convertiría en otro origen: `//otro`, `/\otro`, tabuladores o saltos de línea
 * (`/<TAB>/otro`), espacios y caracteres de control, y cualquier URL absoluta. Devuelve la ruta normalizada o null.
 */
export function rutaInterna(valor: unknown, origen = globalThis.location?.origin ?? 'http://localhost'): string | null {
  if (typeof valor !== 'string' || !/^\/(?![/\\])[^\u0000- \u007f\\]*$/.test(valor)) return null;
  try {
    const u = new URL(valor, origen);
    return u.origin === origen ? u.pathname + u.search + u.hash : null;
  } catch {
    return null;
  }
}
