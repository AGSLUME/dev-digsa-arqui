import { expect, test } from '@playwright/test';
import { crearUsuario, ingresar, ingresarComoAdminPropio, ingresarConClaveInicial, reiniciarMock, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Portado de digsa-java/e2e/pruebas/cuenta-y-seguridad.spec.ts: «Cambiar mi contraseña» sin salir de la aplicación (condición 12), TIME
// OUT editable por el administrador (Anexo 5, 3.2) y desbloqueo de un usuario. Los dos casos del administrador dependen de la pantalla
// Usuarios, que se porta en A5: quedan marcados como pendientes, sin borrarlos (ADR-011).

test.beforeEach(() => reiniciarMock());
test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.E2E.Cuenta.2026';
const PROPIA = 'Propia.E2E.Cuenta.2026';
const NUEVA = 'Nueva.E2E.Cuenta.2026';

/** Crea un auditor con contraseña inicial (en el monolito, por POST /api/usuarios con la sesión del administrador). */
async function crearAuditor(): Promise<string> {
  const usuario = unico('cuenta');
  await crearUsuario({ usuario, nombre: 'Auditor Cuenta de Prueba', perfiles: ['Auditor'], claveInicial: INICIAL });
  return usuario;
}

test('cambiar mi contraseña: con la actual errada avisa; con la correcta sigue en la sesión y el próximo ingreso es con la nueva', async ({
  browser,
}) => {
  const usuario = await crearAuditor();
  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await ingresarConClaveInicial(p, usuario, INICIAL, PROPIA);
  await p.getByRole('button', { name: 'Mi contraseña' }).click();
  const dialogo = p.getByRole('dialog', { name: 'Cambiar mi contraseña' });
  await dialogo.getByLabel('Contraseña actual').fill('No.Es.La.Actual.2026');
  // La etiqueta lleva el asterisco de obligatorio: por el nombre accesible, como en apoyo.ts
  await dialogo.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill(NUEVA);
  await dialogo.getByLabel('Confirme la contraseña nueva').fill(NUEVA);
  await dialogo.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(p.getByTestId('error-mi-clave')).toContainText('La contraseña actual no es correcta.');

  await dialogo.getByLabel('Contraseña actual').fill(PROPIA);
  await dialogo.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(p.getByText('Su contraseña se cambió.')).toBeVisible();
  await expect(dialogo).toHaveCount(0);
  await p.getByRole('link', { name: 'Mis visitas' }).click();
  await expect(p.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  // «Salir» va al ingreso con página completa: se espera esa carga, o el goto del siguiente ingreso choca con ella
  await Promise.all([p.waitForURL(/\/ingreso$/), p.getByRole('button', { name: 'Salir', exact: true }).click()]);
  await expect(p.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();

  await ingresar(p, usuario, NUEVA);
  await salir(p);
  await ctx.close();
});

test('el administrador cambia el TIME OUT en Usuarios › Seguridad y lo restablece', async () => {
  test.fixme(true, 'Pendiente de A5: la pantalla Usuarios › Seguridad (y su contrato en seguridad.yaml)');
});

test('un usuario bloqueado por 5 intentos aparece «Bloqueado» y el administrador lo desbloquea', async () => {
  test.fixme(true, 'Pendiente de A5: la pantalla Usuarios (fila «Bloqueado» y «Desbloquear a …»)');
});

test('celular (360 px): «Mi contraseña» está en el menú y no hay desplazamiento horizontal', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await ingresarComoAdminPropio(page);
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('button', { name: 'Menú' }).click();
  await page.getByRole('button', { name: 'Mi contraseña' }).click();
  await expect(page.getByRole('dialog', { name: 'Cambiar mi contraseña' })).toBeVisible();
  await sinDesplazamientoHorizontal(page);
});
