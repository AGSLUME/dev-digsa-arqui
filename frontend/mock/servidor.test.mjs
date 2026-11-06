// Pruebas del mock (npm run test:mock): solo lo que lo protege cuando corre en el equipo de desarrollo.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { request } from 'node:http';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const PUERTO = 9270 + Math.floor(Math.random() * 9); // 9270-9278: ningún componente de ninguna sesión los usa (ADR-010)
let proceso;

/** Petición cruda (fetch no deja cambiar la cabecera Host). */
function pedir(ruta, { metodo = 'GET', cabeceras = {} } = {}) {
  return new Promise((resolver, rechazar) => {
    const r = request({ host: '127.0.0.1', port: PUERTO, path: ruta, method: metodo, headers: cabeceras }, (res) => {
      res.resume();
      res.on('end', () => resolver(res.statusCode));
    });
    r.on('error', rechazar);
    r.end();
  });
}

before(async () => {
  proceso = spawn(process.execPath, [fileURLToPath(new URL('./servidor.mjs', import.meta.url))], {
    env: { ...process.env, DIGSA_MOCK_PUERTO: String(PUERTO) },
    stdio: 'ignore',
  });
  for (let i = 0; i < 50; i++) {
    try {
      await pedir('/api/auth/inicio');
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  throw new Error('el mock no arrancó');
});

after(() => proceso?.kill());

test('DNS rebinding: con un Host que no es local responde 403', async () => {
  assert.equal(await pedir('/api/auth/inicio', { cabeceras: { Host: 'atacante.example' } }), 403);
  assert.equal(await pedir('/api/auth/inicio', { cabeceras: { Host: `atacante.example:${PUERTO}` } }), 403);
});

test('con Host local (directo o por el proxy de ng serve) atiende', async () => {
  assert.equal(await pedir('/api/auth/inicio', { cabeceras: { Host: `127.0.0.1:${PUERTO}` } }), 200);
  assert.equal(await pedir('/api/auth/inicio', { cabeceras: { Host: 'localhost:4220' } }), 200);
});

test('el control /__mock/* exige la cabecera propia', async () => {
  assert.equal(await pedir('/__mock/reiniciar', { metodo: 'POST' }), 403);
  assert.equal(await pedir('/__mock/reiniciar', { metodo: 'POST', cabeceras: { 'X-Mock-Control': '1' } }), 204);
});

test('una escritura sin CSRF responde 419', async () => {
  assert.equal(await pedir('/api/auth/ingreso', { metodo: 'POST' }), 419);
});

test('contrato 0.4.0: la consulta del enlace es POST /api/auth/clave/consultar (el GET de la URL ya no existe)', async () => {
  assert.equal(await pedir('/api/auth/clave?tkn=x'), 404);
  // Sin CSRF, el POST responde 419 (existe y exige la cabecera)
  assert.equal(await pedir('/api/auth/clave/consultar', { metodo: 'POST' }), 419);
});
