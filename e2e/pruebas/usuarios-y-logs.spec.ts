import { expect, test } from '@playwright/test';
import { ADMIN, DOMINIO, enlaceDelCorreo, ingresar, reiniciarMock, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Portado de digsa-java/e2e/pruebas/usuarios-y-logs.spec.ts: Anexo 5, prueba 2 (usuarios por la interfaz, contraseñas cifradas) y
// prueba 15 (logs en el navegador, con descarga y correo). También el caso del teléfono de alertas.spec.ts:91, que es del formulario de
// Usuarios. Cambios por el sistema nuevo: el enlace para definir la contraseña se lee del correo (Mailpit) y no del aviso, y los correos
// son @angular.prueba.local (también el destino del reporte de logs, que solo se envía a ese dominio en el mock).

test.beforeEach(() => reiniciarMock());
test.afterEach(async ({ page }) => salir(page));

test('el administrador crea un auditor; el auditor define su contraseña con el enlace e ingresa con su perfil', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('auditor');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Auditor Uno de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}${DOMINIO}`);
  await dialogo.getByLabel('Documento').fill('80123456');
  await dialogo.getByLabel('Auditor', { exact: true }).check();
  const desde = Date.now();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText(/Usuario creado/)).toBeVisible();
  const enlace = await enlaceDelCorreo(`${usuario}${DOMINIO}`, desde);
  await expect(page.getByTestId('usuario-' + usuario)).toBeVisible();

  // Validación: el mismo usuario otra vez
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  await dialogo.getByLabel('Nombre completo').fill('Duplicado');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`otro.${usuario}${DOMINIO}`);
  await dialogo.getByLabel('Coordinador', { exact: true }).check();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByTestId('error-usuario')).toContainText('Ya existe un usuario con ese nombre de usuario.');

  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await p2.goto(new URL(enlace).pathname + new URL(enlace).search);
  await p2.getByLabel('Contraseña nueva').fill('Auditor.Prueba.2026');
  await p2.getByLabel('Confirme la contraseña').fill('Auditor.Prueba.2026');
  await p2.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(p2.getByTestId('clave-ok')).toBeVisible();
  await ingresar(p2, usuario, 'Auditor.Prueba.2026');
  await expect(p2.getByRole('link', { name: 'Mis visitas' })).toBeVisible();
  await expect(p2.getByRole('link', { name: 'Usuarios' })).toHaveCount(0);
  await salir(p2);
  await otro.close();
});

test('un teléfono que no es móvil colombiano muestra el error del servidor (alertas.spec.ts:91)', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('telefono');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Usuario Teléfono de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}${DOMINIO}`);
  await dialogo.getByLabel('Teléfono móvil').fill('601 000 0000');
  await dialogo.getByLabel('Auditor', { exact: true }).check();
  await expect(dialogo.getByText(/móvil colombiano: \+57 y 10 dígitos/).first()).toBeVisible();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByTestId('error-usuario')).toContainText('alertas críticas');
  await expect(page.getByTestId('usuario-' + usuario)).toHaveCount(0);
});

test('logs: se ven en pantalla con usuario, fecha, operación y detalle; se descargan en Excel y se envían por correo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Logs del sistema' }).click();
  await page.getByRole('button', { name: 'Buscar' }).click();
  const tabla = page.getByTestId('tabla-logs');
  await expect(tabla.getByRole('cell', { name: 'login', exact: true }).first()).toBeVisible();
  await expect(tabla.getByRole('cell', { name: /^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}$/ }).first()).toBeVisible();
  const [descarga] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Descargar Excel' }).click()]);
  expect(descarga.suggestedFilename()).toMatch(/^logs_auditoria_\d{8}_\d{4}\.xlsx$/);
  await page.getByRole('button', { name: 'Enviar por correo' }).click();
  await page.getByLabel('Correo de destino').fill(`evaluador${DOMINIO}`);
  const respuesta = page.waitForResponse((r) => r.url().includes('/api/logs/enviar'));
  await page.getByRole('button', { name: 'Enviar', exact: true }).click();
  expect((await respuesta).status()).toBe(200);
  await expect(page.getByText(`Reporte enviado a evaluador${DOMINIO}.`)).toBeVisible();
});

for (const [nombre, ancho] of [
  ['celular', 360],
  ['tableta', 768],
  ['escritorio', 1280],
] as const) {
  test(`responsivo ${nombre} (${ancho} px): sin desplazamiento horizontal en ingreso, inicio y logs`, async ({ page }) => {
    await page.setViewportSize({ width: ancho, height: 800 });
    // Sin sesión, «/» navega (página completa) al ingreso del emisor: se mide ya en el ingreso
    await page.goto('/');
    await page.waitForURL(/\/ingreso$/);
    await expect(page.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
    await sinDesplazamientoHorizontal(page);
    await ingresar(page, ...ADMIN);
    await sinDesplazamientoHorizontal(page);
    await page.goto('/logs');
    await expect(page.getByRole('heading', { name: 'Logs del sistema' })).toBeVisible();
    await sinDesplazamientoHorizontal(page);
  });

  // Lo mismo con Usuarios (criterio de A5: funciona a 360 px): la tabla, el formulario y Roles adicionales
  test(`responsivo ${nombre} (${ancho} px): sin desplazamiento horizontal en Usuarios, su formulario y Roles adicionales`, async ({ page }) => {
    await page.setViewportSize({ width: ancho, height: 800 });
    await ingresar(page, ...ADMIN);
    await page.goto('/usuarios');
    await expect(page.getByRole('heading', { name: 'Usuarios' })).toBeVisible();
    await expect(page.getByTestId('usuario-admin.angular')).toBeVisible();
    await sinDesplazamientoHorizontal(page);
    await page.getByRole('button', { name: 'Nuevo usuario' }).click();
    await expect(page.getByRole('dialog', { name: 'Nuevo usuario' })).toBeVisible();
    await sinDesplazamientoHorizontal(page);
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await page.getByRole('button', { name: 'Roles adicionales' }).click();
    await expect(page.getByRole('dialog', { name: 'Roles adicionales' }).getByTestId('tabla-roles')).toBeVisible();
    await sinDesplazamientoHorizontal(page);
  });
}
