import { expect, test, type Page } from '@playwright/test';
import { ADMIN, DOMINIO, escribirCodigo, filaDeUsuario, ingresar, paso1, reiniciarMock, salir, unico } from './apoyo';

// Portado de digsa-java/e2e/pruebas/sesion.spec.ts (a su vez de tests/ui/ui_sesion.js): ingreso, aviso de inactividad, «Seguir
// conectado», cierre por inactividad y sesión única. Mismos casos y aserciones; cambian los selectores y que el código llega por correo.

test.beforeEach(() => reiniciarMock());
test.afterEach(async ({ page }) => salir(page));

/**
 * Espera, en tiempo real, el aviso de inactividad: el mock de las e2e tiene 1 minuto de TIME OUT y el aviso sale a los 30 s (la mitad del
 * plazo). No se usa el reloj falso de Playwright: el aviso se sincroniza con el servidor, que ve pasar el tiempo real (UI-42).
 */
async function esperarElAviso(page: Page) {
  const aviso = page.getByRole('dialog', { name: 'Su sesión está por cerrarse' });
  await expect(aviso).toBeVisible({ timeout: 45_000 });
  return aviso;
}

test('ingreso en dos pasos y salir', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await expect(page.getByRole('link', { name: 'Usuarios' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Logs del sistema' })).toBeVisible();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
});

test('contraseña errada: mensaje genérico', async ({ page }) => {
  await paso1(page, ADMIN[0], 'No.Es.La.Clave.2026');
  await expect(page.getByTestId('error-ingreso')).toContainText('Usuario o contraseña incorrectos');
});

test('aviso de inactividad con cuenta regresiva y «Seguir conectado»', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const aviso = await esperarElAviso(page);
  await expect(page.getByTestId('cuenta-regresiva')).toHaveText(/00:\d{2}/);
  await page.getByRole('button', { name: 'Seguir conectado' }).click();
  await expect(aviso).toBeHidden();
});

test('actividad en otra pestaña: el aviso de esta pestaña se cierra', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const aviso = await esperarElAviso(page);
  // Otra pestaña tuvo respuesta del servidor: deja su marca en localStorage (clave compartida); el aviso lo revisa cada segundo
  await page.evaluate(() => localStorage.setItem('digsa.actividad', String(Date.now())));
  await expect(aviso).toBeHidden({ timeout: 5_000 });
});

test('cierre por inactividad: vuelve al ingreso con el motivo', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Espera real de 1 minuto: basta un motor (el servidor es el mismo)');
  test.setTimeout(150_000);
  await ingresar(page, ...ADMIN);
  await expect(page.getByText('Su sesión está por cerrarse')).toBeVisible({ timeout: 40_000 });
  await expect(page.getByTestId('motivo-cierre')).toContainText('La sesión se cerró por inactividad', { timeout: 60_000 });
});

test('sesión única (decisión 7.1): con una sesión abierta el ingreso desde otro navegador se rechaza; tras «Salir» entra', async ({ browser }) => {
  const equipoA = await browser.newContext();
  const equipoB = await browser.newContext();
  const a = await equipoA.newPage();
  const b = await equipoB.newPage();
  await ingresar(a, ...ADMIN);
  await paso1(b, ...ADMIN);
  await expect(b.getByTestId('error-ingreso')).toContainText('ya tiene una sesión abierta');
  await a.getByRole('link', { name: 'Usuarios' }).click();
  await expect(a.getByRole('heading', { name: 'Usuarios' })).toBeVisible();
  await a.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(a.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
  await ingresar(b, ...ADMIN);
  await salir(b);
  await equipoA.close();
  await equipoB.close();
});

test('contraseña inicial y cierre por el administrador: el usuario la cambia al entrar; el administrador cierra su sesión', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('inicial');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Usuario Inicial de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}${DOMINIO}`);
  await dialogo.getByLabel('Coordinador', { exact: true }).check();
  await dialogo.getByText('Asignar contraseña inicial (se cambia al primer ingreso)').click();
  await dialogo.getByRole('textbox', { name: 'Contraseña inicial' }).fill('Inicial.Asignada.2026');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText(/Usuario creado con contraseña inicial/)).toBeVisible();

  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await paso1(p2, usuario, 'Inicial.Asignada.2026');
  await p2.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill('Propia.Del.Usuario.2026');
  await p2.getByLabel('Confirme la contraseña nueva').fill('Propia.Del.Usuario.2026');
  const desde = Date.now();
  await p2.getByRole('button', { name: 'Cambiar contraseña y continuar' }).click();
  await escribirCodigo(p2, usuario, desde);
  await expect(p2.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();

  await page.reload();
  const fila = await filaDeUsuario(page, usuario);
  await expect(fila.getByText('Sesión abierta')).toBeVisible();
  page.once('dialog', (d) => void d.accept());
  await fila.getByRole('button', { name: /Cerrar la sesión de/ }).click();
  await expect(page.getByText('Sesión cerrada.')).toBeVisible();
  await p2.reload();
  await expect(p2.getByTestId('motivo-cierre')).toContainText('Su sesión fue cerrada');
  await otro.close();
});
