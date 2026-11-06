import { expect, test, type Locator, type Page } from '@playwright/test';
import { sinDesplazamientoHorizontal } from './apoyo';

// Componentes del design system que necesita A5 (selector con búsqueda, casillas, interruptor, numérico, fecha, paginación y aviso
// persistente), en el catálogo de desarrollo (/__catalogo, solo en ng serve). No vienen del monolito: comprueban en navegador real el
// patrón combobox de ARIA y el teclado, que jsdom no puede. Las pantallas de A5 los usarán con los selectores del monolito.

/**
 * Texto de la opción resaltada (`aria-activedescendant`), leído DE NUEVO en cada intento: el atributo cambia en el siguiente ciclo de
 * detección de cambios después de la tecla, así que leerlo una sola vez justo después puede dar la opción anterior (prueba intermitente).
 */
function opcionActiva(page: Page, combo: Locator) {
  return expect.poll(async () => {
    const id = await combo.getAttribute('aria-activedescendant');
    return id ? ((await page.locator(`#${id}`).textContent()) ?? '').trim() : null;
  });
}

test.beforeEach(async ({ page }) => {
  await page.goto('/__catalogo');
  await expect(page.getByRole('heading', { name: 'Catálogo de componentes' })).toBeVisible();
});

test('selector sin búsqueda, solo con teclado: abre, recorre, salta por letra, elige con Enter y Escape no elige', async ({ page }) => {
  const combo = page.getByRole('combobox', { name: 'Fuerza (Sanidad)' });
  await combo.focus();
  await expect(combo).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('ArrowDown');
  await expect(combo).toHaveAttribute('aria-expanded', 'true');
  const lista = page.getByRole('listbox', { name: 'Fuerza (Sanidad)' });
  await expect(lista.getByRole('option')).toHaveCount(4);
  await opcionActiva(page, combo).toBe('Ejército');
  await page.keyboard.press('ArrowDown');
  await opcionActiva(page, combo).toBe('Armada');
  await page.keyboard.press('End');
  await opcionActiva(page, combo).toBe('Policía');
  await page.keyboard.press('Home');
  await opcionActiva(page, combo).toBe('Ejército');
  await page.keyboard.press('f');
  await opcionActiva(page, combo).toBe('Fuerza Aérea');
  await page.keyboard.press('Escape');
  await expect(combo).toHaveAttribute('aria-expanded', 'false');
  await expect(combo).toHaveValue('');
  await page.keyboard.press('ArrowUp'); // ↑ cerrado abre en la última
  await opcionActiva(page, combo).toBe('Policía');
  await page.keyboard.press('Enter');
  await expect(combo).toHaveValue('Policía');
  await expect(combo).toBeFocused();
  await expect(page.getByTestId('valores')).toContainText('fuerza=PON');
  // Al reabrir, la elegida está marcada y resaltada
  await page.keyboard.press('ArrowDown');
  await expect(lista.getByRole('option', { name: 'Policía', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('Tab'); // Tab cierra sin cambiar
  await expect(combo).toHaveAttribute('aria-expanded', 'false');
  await expect(combo).toHaveValue('Policía');
});

test('selector sin búsqueda: Espacio abre y elige (APG select-only); lo tecleado seguido se acumula', async ({ page }) => {
  const combo = page.getByRole('combobox', { name: 'Fuerza (Sanidad)' });
  await combo.focus();
  await page.keyboard.press(' ');
  await expect(combo).toHaveAttribute('aria-expanded', 'true');
  await page.keyboard.type('ar');
  await opcionActiva(page, combo).toBe('Armada');
  await page.waitForTimeout(600);
  await page.keyboard.press(' ');
  await expect(combo).toHaveValue('Armada');
  await expect(combo).toHaveAttribute('aria-expanded', 'false');
});

test('selector con búsqueda: filtra sin tildes, anuncia los resultados, elige con el teclado y con el puntero; «Borrar la selección»', async ({ page }) => {
  const combo = page.getByRole('combobox', { name: 'Establecimiento (ESM)' });
  await combo.click();
  await expect(combo).toHaveAttribute('aria-autocomplete', 'list');
  await expect(page.getByRole('status').filter({ hasText: 'resultados' })).toHaveText('30 resultados');
  await combo.fill('bogota');
  const opciones = page.getByRole('listbox', { name: 'Establecimiento (ESM)' }).getByRole('option');
  await expect(opciones).toHaveCount(10);
  await expect(page.getByRole('status').filter({ hasText: 'resultados' })).toHaveText('10 resultados');
  await combo.fill('ESM 04');
  await expect(opciones).toHaveCount(1);
  await expect(page.getByRole('status').filter({ hasText: 'resultado' })).toHaveText('1 resultado');
  await page.keyboard.press('Enter');
  await expect(combo).toHaveValue('ESM 04 · Establecimiento ficticio Bogotá');
  await combo.fill('zzz');
  await expect(page.getByText('Sin resultados', { exact: true }).first()).toBeVisible();
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await expect(combo).toHaveValue('ESM 04 · Establecimiento ficticio Bogotá'); // lo escrito se descarta
  await combo.click();
  await page.getByRole('option', { name: 'ESM 08 · Establecimiento ficticio Medellín', exact: true }).click();
  await expect(page.getByTestId('valores')).toContainText('esm=8');
  await page.getByRole('button', { name: 'Borrar la selección' }).click();
  await expect(combo).toHaveValue('');
  await expect(combo).toBeFocused();
});

test('selector dentro de un modal: la lista no queda recortada; Escape cierra primero la lista y después el modal', async ({ page }) => {
  await page.getByRole('button', { name: 'Abrir modal' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Selector en un modal' });
  const combo = dialogo.getByRole('combobox', { name: 'Regional (OPL regional)' });
  await expect(combo).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('option', { name: 'Regional Occidente', exact: true })).toBeInViewport();
  await page.keyboard.press('Escape');
  await expect(combo).toHaveAttribute('aria-expanded', 'false');
  await expect(dialogo).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialogo).toBeHidden();
});

test('casillas, interruptor, numérico y fecha: con el teclado y con los selectores que usan las e2e del monolito', async ({ page }) => {
  await page.getByLabel('Auditor', { exact: true }).check();
  await page.getByLabel('Administrador', { exact: true }).check();
  await page.getByText('Asignar contraseña inicial (se cambia al primer ingreso)').click();
  await expect(page.getByRole('switch', { name: 'Asignar contraseña inicial (se cambia al primer ingreso)' })).toBeChecked();
  const minutos = page.getByTestId('minutos-sesion');
  await minutos.fill('-2,5x');
  await expect(minutos).toHaveValue('25');
  const desde = page.getByRole('textbox', { name: 'Desde' });
  await desde.pressSequentially('31022026');
  await expect(desde).toHaveValue('31/02/2026');
  await page.getByRole('button', { name: 'Validar' }).click();
  await expect(page.getByTestId('error-catalogo')).toContainText('Revise los 2 campos marcados');
  await expect(page.getByText('Esa fecha no existe en el calendario.')).toBeVisible();
  // El foco va al primer campo con error: el selector de la fuerza
  await expect(page.getByRole('combobox', { name: 'Fuerza (Sanidad)' })).toBeFocused();
  await desde.fill('');
  await desde.pressSequentially('01112026');
  await expect(desde).toHaveValue('01/11/2026');
  await expect(page.getByTestId('valores')).toContainText('perfiles=1,3');
  await expect(page.getByTestId('valores')).toContainText('desde=2026-11-01');
  await expect(page.getByTestId('valores')).toContainText('clave=true');
});

test('paginación y aviso persistente', async ({ page }) => {
  await expect(page.getByTestId('total-registros')).toHaveText('Mostrando 1–25 de 60 registros');
  const paginas = page.getByRole('navigation', { name: 'Páginas de registros' });
  await expect(paginas.getByRole('button', { name: 'Página anterior' })).toBeDisabled(); // aria-disabled
  await paginas.getByRole('button', { name: 'Página siguiente' }).click();
  await expect(page.getByTestId('total-registros')).toHaveText('Mostrando 26–50 de 60 registros');
  await expect(paginas.getByRole('button', { name: 'Página 2', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: 'Aviso persistente' }).click();
  const aviso = page.getByText('Usuario creado, pero el correo de bienvenida no salió.');
  await expect(aviso).toBeVisible();
  await page.waitForTimeout(5000); // el verde se habría ido a los 4 s
  await expect(aviso).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar el aviso' }).click();
  await expect(aviso).toBeHidden();
});

test('celular (360 px): los componentes no desbordan y la lista del selector cabe', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('combobox', { name: 'Establecimiento (ESM)' }).click();
  await expect(page.getByRole('option').first()).toBeInViewport();
  await sinDesplazamientoHorizontal(page);
});
