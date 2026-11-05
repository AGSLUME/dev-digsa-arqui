import type { NombreIcono } from '../ui/trazos-iconos';
import type { ItemMenu } from './sesion';

/**
 * Portado de frontend/src/shared/menu.ts del monolito; los íconos van por nombre (ui/trazos-iconos.ts).
 * Menú agrupado por módulos (fase U7, pedido del usuario; reemplaza los 4 grupos del mockup de UI-7), con ícono por pantalla y numeral
 * del Anexo 5, asignados aquí por ruta sin tocar `Pantallas.java`. El servidor sigue decidiendo QUÉ pantallas ve cada perfil (3.7); esto
 * solo dice DÓNDE y CÓMO se muestran. Un módulo sin pantallas para el perfil no se muestra. Una ruta que no esté aquí (una pantalla nueva
 * de otra sesión) sale en el módulo que corresponda al grupo del servidor (`GRUPO_DEL_SERVIDOR`), al final de él y con un ícono genérico.
 */
export type GrupoMenu = 'Visitas' | 'Interventoría y seguimiento presupuestal' | 'Reportes' | 'Seguridad' | 'Administración' | 'Mi cuenta';

export const ORDEN_GRUPOS: string[] = ['Visitas', 'Interventoría y seguimiento presupuestal', 'Reportes', 'Seguridad', 'Administración', 'Mi cuenta'];

/** «Mi cuenta» no viene del servidor: la arma la Plantilla con «Mi contraseña» al final del menú. */
export const GRUPO_CUENTA: GrupoMenu = 'Mi cuenta';

/**
 * Grupo del servidor (`Pantallas.java`) → módulo del menú, para las pantallas que aún no están en `PRESENTACION`: la analítica y las
 * cuentas médicas, si llegan con el grupo «Presupuesto», caen en «Interventoría y seguimiento presupuestal» sin cambiar este archivo.
 */
const GRUPO_DEL_SERVIDOR: Record<string, GrupoMenu> = {
  Visitas: 'Visitas',
  Presupuesto: 'Interventoría y seguimiento presupuestal',
  Configuración: 'Administración',
};

interface Presentacion {
  grupo: GrupoMenu;
  icono: NombreIcono;
  /** Numeral del Anexo 5 (se muestra a la derecha, oculto para lectores de pantalla). */
  numeral?: string;
}

/** En el orden en que se muestran dentro de cada módulo. */
const PRESENTACION: Record<string, Presentacion> = {
  agenda: { grupo: 'Visitas', icono: 'calendario-evento', numeral: '4.5' },
  propuesta: { grupo: 'Visitas', icono: 'lista-detalles', numeral: '4.5' },
  'mis-visitas': { grupo: 'Visitas', icono: 'marcador-mapa', numeral: '4.5' },
  calendario: { grupo: 'Visitas', icono: 'calendario-hora', numeral: '4.5' },
  solicitudes: { grupo: 'Visitas', icono: 'notas', numeral: '4.5' },
  hallazgos: { grupo: 'Visitas', icono: 'archivo-buscar', numeral: '4.5' },
  prorrogas: { grupo: 'Visitas', icono: 'portapapeles', numeral: '4.5' },
  opl: { grupo: 'Visitas', icono: 'responder', numeral: '4.5' },
  presupuesto: { grupo: 'Interventoría y seguimiento presupuestal', icono: 'reporte-dinero', numeral: '4.6' },
  dashboard: { grupo: 'Reportes', icono: 'grafico-lineas', numeral: '2.8' },
  riesgo: { grupo: 'Reportes', icono: 'alerta', numeral: '2.8' },
  mapas: { grupo: 'Reportes', icono: 'mapa', numeral: '2.8' },
  reportes: { grupo: 'Reportes', icono: 'grafico-barras', numeral: '2.8' },
  usuarios: { grupo: 'Seguridad', icono: 'usuarios', numeral: '3' },
  logs: { grupo: 'Seguridad', icono: 'escudo', numeral: '3' },
  maestros: { grupo: 'Administración', icono: 'hospital' },
  catalogos: { grupo: 'Administración', icono: 'ajustes' },
  alertas: { grupo: 'Administración', icono: 'campana' },
  exportacion: { grupo: 'Administración', icono: 'exportar' },
  // --- Correos (sesión de correos) ---
  correos: { grupo: 'Administración', icono: 'correo' },
  // --- fin correos ---
};

const ORDEN_RUTAS = Object.keys(PRESENTACION);

export interface EntradaMenu extends ItemMenu {
  grupoVisible: string;
  icono: NombreIcono;
  numeral?: string;
}

export function presentar(m: ItemMenu): EntradaMenu {
  const p = PRESENTACION[m.ruta];
  const grupo = p?.grupo ?? (m.grupo ? (GRUPO_DEL_SERVIDOR[m.grupo] ?? m.grupo) : 'Administración');
  return { ...m, grupoVisible: grupo, icono: p?.icono ?? 'archivo-analitica', numeral: p?.numeral };
}

/**
 * Módulos en el orden de U7 (los grupos que no conoce, al final) con sus entradas en el orden de `PRESENTACION` (las que no conoce, al
 * final de su módulo y en el orden del servidor).
 */
export function agrupar(menu: ItemMenu[]): { grupo: string; entradas: EntradaMenu[] }[] {
  const entradas = menu
    .filter((m) => m.ruta !== 'inicio')
    .map((m, i) => ({ e: presentar(m), i }))
    .sort((a, b) => rango(ORDEN_RUTAS, a.e.ruta) - rango(ORDEN_RUTAS, b.e.ruta) || a.i - b.i)
    .map(({ e }) => e);
  const grupos = Array.from(new Set(entradas.map((e) => e.grupoVisible)));
  grupos.sort((a, b) => rango(ORDEN_GRUPOS, a) - rango(ORDEN_GRUPOS, b));
  return grupos.map((grupo) => ({ grupo, entradas: entradas.filter((e) => e.grupoVisible === grupo) }));
}

function rango(orden: string[], valor: string): number {
  const i = orden.indexOf(valor);
  return i < 0 ? orden.length : i;
}

export const ICONO_INICIO = 'inicio';
