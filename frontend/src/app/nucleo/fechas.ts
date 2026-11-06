import { fecha, hoyBogota, sumarDias } from './formato';

/**
 * Fechas que escribe el usuario en pantalla como DD/MM/AAAA (Anexo 5 num. 2.4: fechas en pantalla dd/mm/aaaa, decisión UI-3) y que viajan
 * a la API como «aaaa-mm-dd». En el monolito se elegían en un calendario (Mantine DatePickerInput), que no deja elegir una fecha
 * imposible; aquí también se pueden escribir, así que se validan igual: formato, que la fecha exista y, si aplica, el rango.
 */

export const MENSAJE_FORMATO = 'Escriba la fecha como DD/MM/AAAA.';
export const MENSAJE_NO_EXISTE = 'Esa fecha no existe en el calendario.';

/** «23/10/2026» → «2026-10-23». null si no tiene el formato o la fecha no existe (31/02, 29/02 de un año no bisiesto, mes 13). */
export function leerFecha(texto: string): string | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(texto.trim());
  if (!m) return null;
  const [d, mes, a] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (a < 1900 || mes < 1 || mes > 12 || d < 1) return null;
  const f = new Date(Date.UTC(a, mes - 1, d));
  if (f.getUTCFullYear() !== a || f.getUTCMonth() !== mes - 1 || f.getUTCDate() !== d) return null;
  return `${m[3]}-${m[2]}-${m[1]}`;
}

/** Mensaje de error de una fecha escrita (null si está bien o vacía y no es obligatoria); con `min`/`max` («aaaa-mm-dd»), también el rango. */
export function errorFecha(
  texto: string,
  { obligatoria = false, nombre = 'la fecha', min, max }: { obligatoria?: boolean; nombre?: string; min?: string; max?: string } = {},
): string | null {
  const t = texto.trim();
  if (!t) return obligatoria ? `Escriba ${nombre}.` : null;
  if (!/^\d{2}\/\d{2}\/\d{4}$/.test(t)) return MENSAJE_FORMATO;
  const iso = leerFecha(t);
  if (!iso) return MENSAJE_NO_EXISTE;
  // Las fechas «aaaa-mm-dd» se comparan como texto
  if (min && iso < min) return `La fecha debe ser desde el ${fecha(min)}.`;
  if (max && iso > max) return `La fecha debe ser hasta el ${fecha(max)}.`;
  return null;
}

/** «aaaa-mm-dd» → «dd/mm/aaaa» para el campo (lo que no es fecha queda vacío). */
export function aTextoFecha(iso: string | null | undefined): string {
  return iso && /^\d{4}-\d{2}-\d{2}$/.test(iso) ? fecha(iso) : '';
}

/** Días de calendario entre dos fechas «aaaa-mm-dd» (Logs.tsx del monolito: `diasEntre`). */
export function diasEntre(desde: string, hasta: string): number {
  const utc = (iso: string) => {
    const [a, m, d] = iso.split('-').map(Number);
    return Date.UTC(a!, m! - 1, d!);
  };
  return Math.round((utc(hasta) - utc(desde)) / 86_400_000);
}

/**
 * Periodo de un filtro con las reglas del servidor (ControladorLogs.filtro del monolito, que repite Logs.tsx): sin «desde», 7 días antes
 * de hoy en Bogotá; sin «hasta», hoy; si están invertidas se intercambian. Devuelve el periodo ordenado.
 */
export function periodo(desde: string | null, hasta: string | null, hoy = hoyBogota(), diasPorOmision = 7): { desde: string; hasta: string } {
  const [d, h] = [desde ?? sumarDias(hoy, -diasPorOmision), hasta ?? hoy].sort();
  return { desde: d!, hasta: h! };
}
