import type { agrupar } from './menu';

/**
 * Migas de la ruta actual y avatar de la cabecera. Portado de frontend/src/shared/Plantilla.tsx (`migasDe`, `iniciales`) del monolito.
 */

/**
 * Grupo del menú y pantalla; los detalles que no están en el menú (la visita, el seguimiento, el detalle desde agenda, calendario, mis
 * visitas u OPL) agregan su nivel para que `aria-current` quede solo en el último.
 */
export function migasDe(pathname: string, grupos: ReturnType<typeof agrupar>): string[] {
  if (pathname === '/') return ['Inicio'];
  const entradas = grupos.flatMap((g) => g.entradas.map((e) => ({ grupo: g.grupo, e })));
  const de = (ruta: string) => entradas.find(({ e }) => e.ruta === ruta);
  if (/^\/visita\/[^/]+/.test(pathname)) return ['Visitas', 'Visita'];
  if (pathname.startsWith('/seguimiento')) {
    const agenda = de('agenda');
    return agenda ? [agenda.grupo, agenda.e.titulo, 'Seguimiento'] : ['Visitas', 'Seguimiento'];
  }
  const ruta = pathname.split('/')[1] ?? '';
  const actual = de(ruta);
  if (!actual) return [];
  return /^\/[^/]+\/visita\/[^/]+/.test(pathname) ? [actual.grupo, actual.e.titulo, 'Detalle de la visita'] : [actual.grupo, actual.e.titulo];
}

/** Iniciales para el avatar (dos letras: nombre y primer apellido). */
export function iniciales(nombre: string): string {
  const partes = nombre.trim().split(/\s+/).filter(Boolean);
  const letras = partes.length > 1 ? [partes[0]!, partes[1]!] : partes;
  return letras.map((p) => p.charAt(0).toUpperCase()).join('') || '?';
}
