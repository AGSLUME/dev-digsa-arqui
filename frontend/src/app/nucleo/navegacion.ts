/**
 * Validación de direcciones a las que la SPA navega con un valor que viene de afuera (la consulta `?volver=`, el `continuar` del servidor):
 * solo rutas del mismo origen. Rechaza lo que el navegador convertiría en otro origen: `//otro`, `/\otro`, tabuladores o saltos de línea
 * (`/<TAB>/otro`), espacios y caracteres de control, y cualquier URL absoluta. Valida la entrada **y la salida**: `new URL()` resuelve los
 * segmentos con punto (`/..//otro`, `/%2e%2e//otro`), que pueden terminar en `//otro`. Devuelve la ruta normalizada o null.
 */
export function rutaInterna(valor: unknown, origen = globalThis.location?.origin ?? 'http://localhost'): string | null {
  if (typeof valor !== 'string' || !/^\/(?![/\\])[^\u0000- \u007f\\]*$/.test(valor)) return null;
  try {
    const u = new URL(valor, origen);
    if (u.origin !== origen) return null;
    const ruta = u.pathname + u.search + u.hash;
    // La ruta resuelta tampoco puede empezar por «//» o «/\» (el navegador la leería como otro origen)
    return /^\/(?![/\\])/.test(ruta) ? ruta : null;
  } catch {
    return null;
  }
}

/** ¿`ruta` es el segmento `prefijo` exacto, o lo sigue «?», «/» o «#»? (`/oauth2/authorize` sí; `/oauth2/authorizeX` no). */
export function empiezaPorSegmento(ruta: string, prefijo: string): boolean {
  return ruta === prefijo || (ruta.startsWith(prefijo) && /^[?/#]/.test(ruta.slice(prefijo.length)));
}
