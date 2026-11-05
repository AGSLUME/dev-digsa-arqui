/**
 * Design system LUME (LUME_design_system.md; ADR-011 y ADR-013): los mismos valores de `src/styles/lume.css` (variables `--lume-*`) y
 * de `src/styles/complementos.css`, para el código que necesita el color como valor (gráficos, pruebas de contraste). Las pantallas y
 * los componentes usan las variables CSS, no estas constantes. `tema.spec.ts` comprueba que coinciden y que llegan a AA.
 * Portado de frontend/src/shared/tema.ts del monolito sin la parte de Mantine.
 */
export const LUME = {
  blue: '#1D4ED8',
  blueDark: '#1E293B',
  blueActive: '#2563EB',
  bg: '#F4F6F9',
  white: '#FFFFFF',
  border: '#E2E8F0',
  text: '#0F172A',
  textMuted: '#64748B',
  edit: '#2563EB',
  successBg: '#DCEFE1',
  successText: '#15803D',
  danger: '#B91C1C',
  dangerBg: '#F5DEDE',
  warningBg: '#F5E8D3',
  warningText: '#92400E',
  infoBg: '#DBEAFE',
  infoText: '#1D4ED8',
  neutralBg: '#E2E8F0',
  neutralText: '#475569',
  radius: '10px',
  shadow: '0 1px 3px rgba(0,0,0,0.06)',
} as const;

/** Colores que la misma especificación usa en sus componentes (no son tokens, pero tampoco se inventan). */
export const COMPLEMENTOS = {
  /** Texto del sidebar. */
  sidebarTexto: '#CBD5E1',
  /** Subtítulo y títulos de sección del sidebar (el #64748B de la especificación no llega a AA sobre #1E293B). */
  sidebarApagado: '#94A3B8',
  encabezadoTabla: '#FAFBFC',
  filaHover: '#F8FAFC',
  primarioDeshabilitado: '#93C5FD',
  bordeAlertaWarning: '#E3CDA3',
  bordeAlertaInfo: '#BFDBFE',
  bordeAlertaSuccess: '#BEDBC6',
  bordeAlertaDanger: '#E3C3C3',
} as const;

/**
 * Ajustes de contraste AA con colores de la misma paleta (aprobados por la integradora del monolito el 2026-10-27, UI-35):
 * - texto apagado en #475569 (--lume-neutral-text): el #64748B no llega a 4,5:1 sobre --lume-bg (4,40);
 * - borde de los campos en #64748B (--lume-text-muted): --lume-border no llega a 3:1 (WCAG 1.4.11) y es lo único que marca el campo;
 * - texto de la insignia de éxito en --lume-text (el verde sobre su fondo da 4,17); el punto y el fondo siguen verdes.
 */
export const APAGADO = LUME.neutralText;
export const BORDE_CAMPO = LUME.textMuted;
/** Texto de ejemplo de los campos (placeholder): el apagado, que llega a 4,5:1 sobre blanco y sobre --lume-bg. */
export const PLACEHOLDER = APAGADO;

/** Las 5 variantes de estado de LUME (insignias y alertas). */
export type Variante = 'success' | 'warning' | 'info' | 'neutral' | 'danger';

export const VARIANTES: Record<Variante, { fondo: string; texto: string; punto: string; borde: string }> = {
  success: { fondo: LUME.successBg, texto: LUME.text, punto: LUME.successText, borde: COMPLEMENTOS.bordeAlertaSuccess },
  warning: { fondo: LUME.warningBg, texto: LUME.warningText, punto: LUME.warningText, borde: COMPLEMENTOS.bordeAlertaWarning },
  info: { fondo: LUME.infoBg, texto: LUME.infoText, punto: LUME.infoText, borde: COMPLEMENTOS.bordeAlertaInfo },
  neutral: { fondo: LUME.neutralBg, texto: LUME.neutralText, punto: LUME.neutralText, borde: LUME.border },
  danger: { fondo: LUME.dangerBg, texto: LUME.danger, punto: LUME.danger, borde: COMPLEMENTOS.bordeAlertaDanger },
};

/**
 * Las pantallas del monolito nombran el color como Mantine (`red`, `orange`, `yellow`, `green`, `blue`, `gray`, `lume`). Al portar una
 * pantalla se conserva ese nombre y aquí se traduce a la variante LUME; lo que no se conoce es «info».
 */
const VARIANTE_DE_COLOR: Record<string, Variante> = {
  green: 'success',
  orange: 'warning',
  yellow: 'warning',
  blue: 'info',
  lume: 'info',
  gray: 'neutral',
  red: 'danger',
};

export function variante(color: string | undefined): Variante {
  const nombre = (color ?? 'lume').split('.')[0]!;
  return Object.hasOwn(VARIANTE_DE_COLOR, nombre) ? VARIANTE_DE_COLOR[nombre]! : 'info';
}
