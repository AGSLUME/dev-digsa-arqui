# Pendientes que deja la fase I1 (integradora, 2026-11-05)

Salen de la revisión de código del esqueleto (agentes revisor-java + seguridad). Lo corregible en el esqueleto ya está corregido en `develop`; esto es lo que depende de trabajo de otras fases.

## Bloqueantes de S1 (sesión seguridad): la fase S1 no se aprueba sin ellos

| # | Severidad | Qué | Dónde |
|---|---|---|---|
| 1 | Alta | **Cierre de sesión completo.** Hoy `/bff/salir` solo invalida `LUME_SESION`; la sesión `LUME_IDP` del emisor sigue viva. En un equipo compartido, el siguiente usuario que pulse «ingresar» entra como el anterior. Hace falta OIDC RP-initiated logout (`OidcClientInitiatedServerLogoutSuccessHandler`, `end_session_endpoint`), revocar el refresh token y responder 204 a la SPA. | gateway `ConfiguracionSeguridadGateway` + emisor |
| 2 | Alta | **Fallo del refresco = 401, nunca 500.** Si el refresh token no sirve (emisor reiniciado, JWK nueva, revocación), el `TokenRelay` lanza `ClientAuthorizationException`: hay que traducirla a 401 `sin_sesion` e invalidar la sesión. JWK y autorizaciones del emisor pasan a base (hoy están en memoria). Mitigación ya aplicada: `reuse-refresh-tokens: true` para el cliente confidencial `gateway`. | gateway + emisor |
| 3 | Media | `aud` por destinatario (gateway, cada servicio, monolito) y claim `sid` validado por el gateway en cada petición (ADR-004). | emisor `ClaimsToken` |
| 4 | Media | `/api/auth/**` sin sesión (`permitAll`) con límite de tasa por IP y por usuario (ADR-003). | gateway |
| 5 | — | Reemplazar todo lo marcado `ANDAMIAJE I1` (usuario en memoria, `uid` ficticio, formulario por defecto). El cliente `humo` y el usuario de desarrollo ya solo existen en el perfil `local`. | emisor |

## Para S2

- `UsuarioActual.alcance()` debe fallar cerrado: un usuario sin alcance no ve datos (hoy devuelve un mapa vacío).

## Ya resuelto en el esqueleto (para que nadie lo deshaga)

- `librerias/comun`: la cadena de recurso se aplica **siempre** y va de última (`@Order(LOWEST_PRECEDENCE)`). Un servicio que necesite reglas propias declara otra cadena con `securityMatcher` y un `@Order` menor. `/api/**` exige token de persona (claim `uid`); los tokens de máquina solo entran a `/interno/**`; lo no declarado queda cerrado.
- Gateway: `RemoveResponseHeader=Set-Cookie` en toda ruta de servicio o del monolito; métricas cerradas en el puerto público; `X-Forwarded-For` lo fija el gateway (no se añade a lo del cliente); cookies `Secure` por defecto (solo el `.env` local pone `LUME_URL_SEGURA=false`).
- Servicios: `X-Forwarded-*` solo se aceptan de proxies internos (`server.tomcat.remoteip.internal-proxies`, variable `LUME_PROXIES_INTERNOS`).

## Para el cierre (I9)

- Prometheus del gateway por un puerto de gestión interno (hoy cerrado en el público).
- `server.forward-headers-strategy` del gateway y `LUME_PROXIES_CONFIABLES` acotados al ingress.
- Análisis de dependencias Maven en el CI (ADR-012). En I1 solo corre `npm audit`; OWASP Dependency-Check necesita clave de NVD y queda para cuando el CI tenga secretos.
- ~~Imágenes `prom/prometheus` y `openzipkin/zipkin`~~: aprobadas por el usuario el 2026-11-05.
