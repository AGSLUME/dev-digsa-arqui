import { readFileSync } from 'node:fs';
import { APAGADO, BORDE_CAMPO, COMPLEMENTOS, LUME, PLACEHOLDER, VARIANTES, variante } from './tema';

// Portado de frontend/src/shared/tema.test.ts del monolito (sin la parte del tema de Mantine).

function luminancia(hex: string): number {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

export function contraste(a: string, b: string): number {
  const [x, y] = [luminancia(a), luminancia(b)].sort((m, n) => n - m);
  return (x! + 0.05) / (y! + 0.05);
}

function variables(archivo: string, prefijo: string): Record<string, string> {
  const css = readFileSync(archivo, 'utf8');
  return Object.fromEntries([...css.matchAll(new RegExp(`--${prefijo}-([a-z-]+):\\s*([^;]+);`, 'g'))].map((m) => [m[1]!, m[2]!.trim()]));
}

const kebab = (k: string) => k.replace(/[A-Z]/g, (l) => '-' + l.toLowerCase());

describe('tema LUME: contraste WCAG 2.2 AA', () => {
  it('texto, apagado y placeholder sobre blanco y sobre el fondo de la página', () => {
    for (const fondo of [LUME.white, LUME.bg]) {
      expect(contraste(LUME.text, fondo)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(APAGADO, fondo)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(PLACEHOLDER, fondo)).toBeGreaterThanOrEqual(4.5);
    }
    // Encabezados de tabla y migas (#64748B) solo van sobre blanco o #FAFBFC; las etiquetas de campo usan el apagado AA
    expect(contraste(LUME.textMuted, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.textMuted, COMPLEMENTOS.encabezadoTabla)).toBeGreaterThanOrEqual(4.5);
  });

  it('borde de los campos a 3:1 (WCAG 1.4.11)', () => {
    expect(contraste(BORDE_CAMPO, LUME.white)).toBeGreaterThanOrEqual(3);
    expect(contraste(BORDE_CAMPO, LUME.bg)).toBeGreaterThanOrEqual(3);
  });

  it('sidebar: texto, títulos de sección, activo y foco blanco', () => {
    expect(contraste(COMPLEMENTOS.sidebarTexto, LUME.blueDark)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(COMPLEMENTOS.sidebarApagado, LUME.blueDark)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.white, LUME.blueActive)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.white, LUME.blueDark)).toBeGreaterThanOrEqual(3);
  });

  it('botones: primario, outline (y su hover), peligro y enlaces', () => {
    expect(contraste(LUME.white, LUME.blueActive)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.white, LUME.blue)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.blueActive, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.blue, LUME.infoBg)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.danger, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.danger, LUME.dangerBg)).toBeGreaterThanOrEqual(4.5);
  });

  it('el anillo de foco (azul activo) llega a 3:1 sobre blanco y sobre el fondo de la página (WCAG 2.4.11)', () => {
    expect(contraste(LUME.blueActive, LUME.white)).toBeGreaterThanOrEqual(3);
    expect(contraste(LUME.blueActive, LUME.bg)).toBeGreaterThanOrEqual(3);
  });

  it.each(Object.entries(VARIANTES))('insignia y alerta %s: texto, y los enlaces (--lume-blue) dentro de la alerta, sobre su fondo', (_n, v) => {
    expect(contraste(v.texto, v.fondo)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.text, v.fondo)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.blue, v.fondo)).toBeGreaterThanOrEqual(4.5);
  });
});

describe('tema LUME: tokens', () => {
  it('los valores de tema.ts son los de lume.css (única fuente), sin tokens de más ni de menos', () => {
    const css = variables('src/styles/lume.css', 'lume');
    for (const [k, v] of Object.entries(LUME)) expect(css[kebab(k)], k).toBe(v);
    expect(Object.keys(css)).toHaveLength(Object.keys(LUME).length);
  });

  it('los complementos de la especificación están en complementos.css con los mismos valores', () => {
    const css = variables('src/styles/complementos.css', 'complemento');
    for (const [k, v] of Object.entries(COMPLEMENTOS)) expect(css[kebab(k)], k).toBe(v);
    expect(Object.keys(css)).toHaveLength(Object.keys(COMPLEMENTOS).length);
  });

  it('los colores del monolito (red, orange, green, blue, gray, lume) se traducen a las 5 variantes LUME', () => {
    expect(variante('red')).toBe('danger');
    expect(variante('orange')).toBe('warning');
    expect(variante('yellow')).toBe('warning');
    expect(variante('green')).toBe('success');
    expect(variante('blue')).toBe('info');
    expect(variante('gray')).toBe('neutral');
    expect(variante(undefined)).toBe('info');
    expect(variante('grape')).toBe('info');
    expect(variante('constructor')).toBe('info');
    expect(variante('red.8')).toBe('danger');
  });
});
