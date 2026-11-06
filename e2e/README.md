# e2e · LUME en Angular (Playwright)

Los casos de `digsa-java/e2e/pruebas` portados a Angular: mismos casos y aserciones de negocio, con los selectores nuevos (ADR-011). Un
caso que todavía no puede pasar (porque su pantalla no está portada) queda con `test.fixme` y el motivo; no se borra.

```bash
npm ci
npx playwright install chromium firefox webkit     # navegadores ligados a la versión fija de @playwright/test (1.64.1)
npx playwright test                                # chromium, firefox y webkit
npx playwright test --project=chromium -g "ingreso"
```

Antes: Mailpit corriendo en 127.0.0.1:1025/8025 (`digsa-java/docs/guia_mailpit.md`) y el cliente generado en el frontend
(`npm run api` en `../frontend`, necesita Java 21). La configuración levanta sola el mock de seguridad (gateway en 9200, 1 minuto de
inactividad) y `ng serve` en 4220; si ya están arriba los reutiliza (fuera del CI).

| Variable | Para qué |
|---|---|
| `DIGSA_E2E_PUERTO` | Puerto de `ng serve` (4220, ADR-010) |
| `DIGSA_GATEWAY_PUERTO` | Puerto del gateway o del mock (9200) |
| `DIGSA_E2E_GATEWAY=real` | No levanta el mock: usa el gateway y `seguridad` reales de la sesión |
| `DIGSA_E2E_ADMIN` | `usuario:clave` del administrador de prueba (por omisión el del mock) |
| `DIGSA_E2E_MAILPIT` | URL de la API de Mailpit (`http://127.0.0.1:8025`) |

Diferencias con el monolito, todas por el sistema nuevo (detalle en `pruebas/apoyo.ts`): el código MFA se lee del correo en Mailpit
filtrando por el destinatario (`@angular.prueba.local`, ADR-010); el captcha es Turnstile con la clave de prueba pública de Cloudflare;
«Salir» es `POST /bff/salir` (ADR-014); los usuarios de prueba se crean por el control del mock hasta que el contrato de usuarios tenga
`POST /api/usuarios`.

| Archivo | Casos | Pendientes |
|---|---|---|
| `sesion.spec.ts` | ingreso en dos pasos, contraseña errada, aviso de inactividad, otra pestaña, cierre por inactividad (solo Chromium: espera real), sesión única | contraseña inicial + cierre por el administrador (A5, pantalla Usuarios) |
| `cuenta-y-seguridad.spec.ts` | cambiar mi contraseña, celular 360 px | TIME OUT y desbloqueo por el administrador (A5) |
| `responsivo-360.spec.ts` | todas las pantallas del menú a 360 px sin desplazamiento horizontal | — |
| `teclado.spec.ts` (nuevo, no viene del monolito) | panel del menú en celular con el teclado (foco, Escape, `inert`) y «Ir al contenido» fuera del inicio | — |
