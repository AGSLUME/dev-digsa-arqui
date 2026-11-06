# ADR-014 · Flujo de ingreso: pantalla Angular + emisor OIDC detrás del gateway

- Estado: Aceptado (integradora, 2026-11-05; decisión técnica que desarrolla ADR-003 y ADR-004) · Decide: integradora
- Afecta a: sesión seguridad (S1) y sesión angular-base (A2, A3)

## Contexto
ADR-004 dice que la pantalla de ingreso es de Angular (`/ingreso`) con los mismos pasos y textos que el monolito, y que termina en la autorización OIDC. El BFF (ADR-003) es el cliente OIDC. Hay que fijar cómo se conectan las dos cosas para que las dos sesiones trabajen en paralelo contra lo mismo.

## Decisión
Todo va por el mismo origen (el gateway). La secuencia:

1. Angular recibe `401` con `motivo: sin_sesion` (o abre la app sin sesión) → **navega la página completa** a `/bff/oauth2/authorization/lume`.
2. Gateway → `/oauth2/authorize` del emisor (`seguridad`) → sin sesión en el emisor, este redirige a **`/ingreso`**, que es la ruta Angular (el emisor configura su `loginPage("/ingreso")`; el gateway enruta `/ingreso` a Angular por `/**`).
3. La pantalla `/ingreso` habla con **`/api/auth/*` de `seguridad`, con las mismas rutas, cuerpos, códigos y textos que el monolito** (`identidad/ControladorIngreso`, verificado en el código): `GET inicio` (clave de sitio de Turnstile + CSRF), `POST ingreso`, `POST codigo`, `POST codigo/reenviar`, `POST cambiar-clave`, `POST olvide`, `GET clave?tkn=` y `POST clave` (definir la clave por enlace). Las **pantallas** Angular se llaman `/ingreso`, `/olvide-clave` y `/definir-clave`; las **API** conservan los nombres del monolito. Esas llamadas crean y completan la sesión del emisor (cookie `LUME_IDP`). La de la cuenta propia sigue siendo `POST /api/cuenta/clave`.
4. Cuando el último paso termina bien (código MFA correcto y, si aplica, la clave inicial cambiada), la respuesta trae **`continuar`**: la URL de `/oauth2/authorize` guardada en el paso 2. Angular hace `window.location.assign(continuar)` → el emisor emite el código → el gateway lo canjea → sesión `LUME_SESION` → vuelve a la app.
5. **Salir**: `POST /bff/salir` (gateway). Cierra la sesión del gateway **y** la del emisor (RP-initiated logout) y revoca el refresh token; responde 204 (bloqueante 1 de S1 en PENDIENTES_I1). `/api/auth/salir` del monolito deja de existir.
6. `/api/sesion`, `/api/sesion/estado`, `/api/sesion/mantener` y `/api/cuenta/clave` siguen como en el monolito, en `seguridad`, por el gateway con `TokenRelay`. Consultar el estado no renueva la sesión (ADR-004).

Rutas en el gateway:

| Ruta | Destino | Seguridad en el gateway |
|---|---|---|
| `/api/auth/**` | `seguridad` | Sin sesión (`permitAll`), **con CSRF del gateway** (`XSRF-TOKEN` / `X-XSRF-TOKEN`), límite de tasa por IP y por usuario. Sin `TokenRelay`. Pasan `Cookie` y `Set-Cookie` de `LUME_IDP` (y solo esa). |
| `/oauth2/**`, `/.well-known/**`, `/userinfo`, `/connect/**` | `seguridad` | Fuera de la cadena del gateway (como en I1). |
| `/ingreso`, `/olvide-clave`, `/definir-clave` y el resto de `/**` | Angular | Públicas. |

- `seguridad` no aplica su propio CSRF en `/api/auth/**` (lo aplica el gateway) y solo acepta esas peticiones desde el gateway (red interna; en local, `LUME_PROXIES_INTERNOS`).
- El formulario por defecto del andamiaje (`/login`) desaparece en S1.
- Captcha: Cloudflare Turnstile como en el monolito. En local, mock y e2e se usan las **claves de prueba públicas que documenta Cloudflare** (siempre aprueban); nunca claves reales en el repo.

## Consecuencias
- (+) Mismos textos, pasos y `data-testid` que el monolito: los e2e de sesión se portan cambiando solo selectores.
- (+) Angular nunca ve un token; el emisor conserva el control de MFA, bloqueo y sesión única.
- (−) El contrato `contratos/seguridad.yaml` debe incluir `continuar` en la respuesta final de `/api/auth/*`. Hasta que exista, angular-base usa un mock con estas mismas rutas.
