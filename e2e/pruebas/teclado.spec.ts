import { expect, test } from '@playwright/test';
import { ingresarComoAdminPropio, reiniciarMock, salir } from './apoyo';

// Teclado y foco del shell (auditoría AA de A2-A3; no está en el monolito: son los cambios C1 y C3 de docs/sesiones/DESVIACIONES_A11Y.md
// y el salto al contenido). Comprueba en navegador real lo que jsdom no puede (media queries, inert, foco).

test.beforeEach(() => reiniciarMock());
test.afterEach(async ({ page }) => salir(page));

test('celular (360 px): «Menú» abre el panel con el foco en la primera entrada; Escape lo cierra y el foco vuelve a «Menú»', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await ingresarComoAdminPropio(page);
  const menu = page.getByRole('button', { name: 'Menú' });
  await menu.click();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  const modulos = page.getByRole('navigation', { name: 'Módulos' });
  await expect(modulos.getByRole('link', { name: 'Inicio' })).toBeFocused();
  // Lo de atrás queda inerte: el foco no cae en controles tapados
  await expect(page.locator('main#contenido')).toHaveAttribute('inert', '');
  await page.keyboard.press('Escape');
  await expect(modulos).toBeHidden();
  await expect(menu).toBeFocused();
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
});

test('«Ir al contenido» lleva el foco al contenido también fuera del inicio, sin salir de la pantalla', async ({ page }) => {
  await ingresarComoAdminPropio(page);
  await page.getByRole('navigation', { name: 'Módulos' }).getByRole('link', { name: 'Usuarios' }).click();
  await expect(page).toHaveURL(/\/usuarios$/);
  // Se enfoca directamente: en WebKit (como en Safari) el Tab no pasa por los enlaces salvo que el usuario lo active
  const saltar = page.getByRole('link', { name: 'Ir al contenido' });
  await saltar.focus();
  await expect(saltar).toBeFocused();
  await expect(saltar).toBeInViewport(); // visible al recibir el foco
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/usuarios$/);
  await expect(page.locator('main#contenido')).toBeFocused();
});
