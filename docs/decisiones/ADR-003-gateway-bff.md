# ADR-003 · Puerta de entrada: Spring Cloud Gateway como BFF con relay del token

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
El monolito protege la SPA con sesión en el servidor, cookie HttpOnly + SameSite=Strict, CSRF en toda escritura y una CSP estricta. Pasar a tokens guardados en el navegador bajaría ese nivel. Además hay que enrutar `/api/analitica` y `/api/presupuesto` al monolito.

## Decisión
`servicios/gateway` con **Spring Cloud Gateway (variante reactiva)** en el papel de **BFF** (Backend for Frontend):
- Es el cliente OAuth2 confidencial (Authorization Code + PKCE) frente al servidor de autorización de `seguridad`.
- El navegador solo tiene la cookie de sesión del gateway (HttpOnly, SameSite=Strict, Secure en HTTPS). **Los tokens nunca llegan al navegador.**
- La sesión del gateway vive en **Redis** (Spring Session) para poder correr varias réplicas.
- Filtro `TokenRelay`: añade `Authorization: Bearer <access token>` hacia cada servicio y hacia el monolito, y refresca el token con el refresh token.
- CSRF con cookie `XSRF-TOKEN` y cabecera `X-XSRF-TOKEN` (el mismo contrato que usa hoy la SPA). CSP y cabeceras de seguridad iguales a `ConfiguracionSeguridad` del monolito.
- Errores 401/403/419 en JSON RFC 7807 con el mismo `motivo` que el monolito (`sin_sesion`, `bloqueado`, ...).

Rutas:

| Ruta | Destino |
|---|---|
| `/oauth2/**`, `/login/**`, `/.well-known/**`, `/api/auth/**` | `seguridad` |
| `/api/<prefijo>` | servicio dueño según ADR-002 |
| `/api/analitica/**`, `/api/presupuesto/**` y su interfaz | monolito digsa-java (ADR-008) |
| `/**` | Angular (archivos estáticos) |

- El gateway mide la inactividad y valida el `sid` en cada petición contra `seguridad`, con caché de 30 s como máximo; cierra la sesión al recibir `sesion.cerrada` (detalle en ADR-004).
- Límite de tasa en `/api/auth/**` y `/login/**` (con Redis), **por IP y por usuario**, y Resilience4j por ruta (ADR-009).
- IP del cliente: el gateway fija `X-Forwarded-For` y los servicios aceptan las cabeceras de reenvío **solo cuando vienen del gateway**. El captcha, `mfa_codigos.ip` y la bitácora usan esa IP.
- Los servicios son **servidores de recursos**: validan el JWT (firma por JWKS, `iss`, `aud`, vencimiento) con `librerias/comun`. No confían en cabeceras de identidad puestas por el gateway.

## Consecuencias
- (+) El navegador queda tan protegido como hoy; Angular no maneja tokens.
- (+) El monolito recibe el mismo token que los servicios nuevos.
- (−) El gateway tiene estado (sesión en Redis): Redis entra desde ya en local y en el cierre necesita alta disponibilidad.
- (−) Una app móvil futura usará el mismo BFF o un cliente público con PKCE; se decide cuando exista.
