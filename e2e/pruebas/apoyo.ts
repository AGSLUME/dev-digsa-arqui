import { expect, type Page } from '@playwright/test';

/**
 * Apoyo de las pruebas, portado de digsa-java/e2e/pruebas/apoyo.ts. Diferencias, todas por el sistema nuevo:
 * - el código MFA ya no viene en el mensaje («local: …»): se lee del correo en Mailpit, filtrando por el destinatario (ADR-010: los
 *   usuarios de esta sesión usan @angular.prueba.local, así no se mezcla con los correos de otras sesiones);
 * - el captcha es Turnstile con la clave de PRUEBA pública de Cloudflare: se espera a que el widget deje su respuesta;
 * - «Salir» es POST /bff/salir (ADR-014);
 * - el enlace para definir la contraseña llega solo por correo (el monolito lo mostraba en el aviso, «(local: …)»): se lee de Mailpit;
 * - los selectores (Select de Mantine) son combobox ARIA: `getByRole('combobox', { name })` en lugar de `textbox`;
 * - `crearUsuario` crea por el control del mock (`/__mock/usuarios`) a los usuarios que una prueba necesita sin pasar por la pantalla
 *   Usuarios; las pruebas de esa pantalla crean por ella o por POST /api/usuarios, como el monolito.
 */

export const MAILPIT = process.env.DIGSA_E2E_MAILPIT ?? 'http://127.0.0.1:8025';
export const DOMINIO = '@angular.prueba.local';
/** Cabecera que exige el control del mock (/__mock/*). */
const CONTROL = { 'X-Mock-Control': '1' };
const GATEWAY = `http://127.0.0.1:${process.env.DIGSA_GATEWAY_PUERTO ?? 9200}`;

export const ADMIN = (process.env.DIGSA_E2E_ADMIN ?? 'admin.angular:Admin.Angular.Prueba.2026').split(':') as [string, string];

/** Deja el mock como recién arrancado (usuarios, sesiones, bloqueos). Con el gateway real no hace nada. */
export async function reiniciarMock(): Promise<void> {
  if (process.env.DIGSA_E2E_GATEWAY === 'real') return;
  const r = await fetch(`${GATEWAY}/__mock/reiniciar`, { method: 'POST', headers: CONTROL });
  expect(r.status, 'reiniciar el mock').toBe(204);
}

/** Crea un usuario de prueba (perfiles por nombre: «Administrador», «Auditor»…), con contraseña inicial que se cambia al entrar. */
export async function crearUsuario(datos: { usuario: string; nombre: string; perfiles: string[]; claveInicial: string }): Promise<void> {
  const r = await fetch(`${GATEWAY}/__mock/usuarios`, {
    method: 'POST',
    headers: { ...CONTROL, 'Content-Type': 'application/json' },
    body: JSON.stringify(datos),
  });
  expect(r.status, await r.text()).toBe(201);
}

interface ResumenCorreo {
  ID: string;
  Created: string;
  To: { Address: string }[];
}

/** El último correo para `destinatario` llegado después de `desde`; espera hasta que llegue. */
export async function ultimoCorreo(destinatario: string, desde: number): Promise<{ asunto: string; texto: string }> {
  let id: string | undefined;
  await expect
    .poll(
      async () => {
        const r = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${destinatario}"`)}&limit=5`);
        const { messages } = (await r.json()) as { messages: ResumenCorreo[] };
        id = messages.find((m) => Date.parse(m.Created) >= desde - 1000)?.ID;
        return id;
      },
      { message: `correo para ${destinatario}`, timeout: 15_000 },
    )
    .toBeTruthy();
  const m = (await (await fetch(`${MAILPIT}/api/v1/message/${id}`)).json()) as { Subject: string; Text: string };
  return { asunto: m.Subject, texto: m.Text };
}

/** El enlace para definir la contraseña del último correo para `destinatario` (bienvenida o restablecer). */
export async function enlaceDelCorreo(destinatario: string, desde: number): Promise<string> {
  const { texto } = await ultimoCorreo(destinatario, desde);
  const enlace = /(https?:\/\/\S+\/definir-clave\?tkn=[a-f0-9]{64})/.exec(texto)?.[1];
  expect(enlace, 'el correo trae el enlace').toBeTruthy();
  return enlace!;
}

/** El código de 6 dígitos del último correo de verificación para el usuario. */
export async function codigoDelCorreo(usuario: string, desde: number): Promise<string> {
  const { texto } = await ultimoCorreo(`${usuario}${DOMINIO}`, desde);
  const codigo = /\b(\d{6})\b/.exec(texto)?.[1];
  expect(codigo, 'el correo trae el código').toBeTruthy();
  return codigo!;
}

/** Espera la respuesta del captcha (Turnstile con la clave de prueba aprueba solo), si la pantalla lo muestra. */
export async function esperarCaptcha(page: Page): Promise<void> {
  const respuesta = page.locator('input[name="cf-turnstile-response"]');
  if (await page.getByText('Captcha desactivado en local').isVisible()) return;
  await expect(respuesta).toHaveCount(1, { timeout: 20_000 });
  await expect.poll(() => respuesta.inputValue(), { timeout: 20_000, message: 'respuesta del captcha de prueba' }).not.toBe('');
}

/** Paso 1 por la pantalla: usuario, contraseña, captcha e «Ingresar». Devuelve la hora del envío (para buscar el correo). */
export async function paso1(page: Page, usuario: string, clave: string): Promise<number> {
  await page.goto('/');
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Contraseña').fill(clave);
  await esperarCaptcha(page);
  const desde = Date.now();
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  return desde;
}

/** Escribe el código del correo en las 6 casillas y verifica. */
export async function escribirCodigo(page: Page, usuario: string, desde: number): Promise<void> {
  const codigo = await codigoDelCorreo(usuario, desde);
  const casillas = page.locator('[data-testid="codigo"] input');
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo[i]!);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
}

/** Ingreso por la pantalla: usuario, contraseña y el código que llega al correo. */
export async function ingresar(page: Page, usuario: string, clave: string): Promise<void> {
  const desde = await paso1(page, usuario, clave);
  await expect(page.getByText('Le enviamos un código de 6 dígitos a su correo.')).toBeVisible();
  await escribirCodigo(page, usuario, desde);
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
}

/** Ingreso de un usuario con contraseña inicial: la cambia en la pantalla y sigue con el código. */
export async function ingresarConClaveInicial(page: Page, usuario: string, inicial: string, nueva: string): Promise<void> {
  await paso1(page, usuario, inicial);
  await page.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill(nueva);
  await page.getByLabel('Confirme la contraseña nueva').fill(nueva);
  const desde = Date.now();
  await page.getByRole('button', { name: 'Cambiar contraseña y continuar' }).click();
  await expect(page.getByText('Contraseña actualizada. Le enviamos un código de 6 dígitos a su correo.')).toBeVisible();
  await escribirCodigo(page, usuario, desde);
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
}

/** Administrador propio de la prueba (como en el monolito, para no retener al compartido). La página queda con él ya ingresado. */
export async function ingresarComoAdminPropio(page: Page, prefijo = 'adm'): Promise<string> {
  const usuario = unico(prefijo);
  await crearUsuario({ usuario, nombre: 'Administrador Propio de Prueba', perfiles: ['Administrador'], claveInicial: 'Inicial.Admin.Propio.2026' });
  await ingresarConClaveInicial(page, usuario, 'Inicial.Admin.Propio.2026', 'Admin.Propio.E2E.2026');
  return usuario;
}

/**
 * Cierra la sesión del navegador con «Salir» por la API (POST /bff/salir). Con la sesión única, una sesión abierta bloquea un segundo
 * ingreso del mismo usuario, así que cada prueba deja libre al usuario. No falla si la página ya no tiene sesión.
 */
export async function salir(page: Page): Promise<void> {
  try {
    if (page.isClosed() || !page.url().startsWith('http')) return;
    await page.evaluate(async () => {
      const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      await fetch('/bff/salir', { method: 'POST', headers: t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {} });
    });
  } catch {
    // sin sesión o página cerrada
  }
}

export function unico(prefijo: string): string {
  return `${prefijo}${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`;
}

/** La fila del usuario en la pantalla Usuarios: lo busca con «Buscar» (puede estar en otra página de la tabla). */
export async function filaDeUsuario(page: Page, usuario: string) {
  await page.getByLabel('Buscar', { exact: true }).fill(usuario);
  return page.getByTestId('usuario-' + usuario);
}

/** Sin desplazamiento horizontal de la página. */
export async function sinDesplazamientoHorizontal(page: Page): Promise<void> {
  // Se reintenta unos segundos: justo después de cambiar el tamaño de la ventana el menú lateral sigue en su transición y desborda
  const medir = () => page.evaluate(() => ({ ancho: document.documentElement.scrollWidth, visible: document.documentElement.clientWidth }));
  await expect
    .poll(
      async () => {
        const r = await medir();
        return r.ancho - r.visible;
      },
      { timeout: 5000 },
    )
    .toBeLessThanOrEqual(0);
}

/** Llamada a la API desde la página (misma cookie de sesión y token CSRF), para preparar datos sin pasar por la pantalla. */
export async function api<T = Record<string, unknown>>(page: Page, metodo: string, ruta: string, cuerpo?: unknown): Promise<{ estado: number; datos: T }> {
  return page.evaluate(
    async ({ metodo, ruta, cuerpo }) => {
      const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      const r = await fetch(ruta, {
        method: metodo,
        headers: { 'Content-Type': 'application/json', ...(t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {}) },
        body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
      });
      return { estado: r.status, datos: await r.json().catch(() => ({})) };
    },
    { metodo, ruta, cuerpo },
  ) as Promise<{ estado: number; datos: T }>;
}
