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
- Imágenes `prom/prometheus` y `openzipkin/zipkin`: pendientes de aprobación del usuario (no son oficiales ni de editor verificado en Docker Hub).
- (Seguridad, S1) CSRF: hoy es doble envío (`CookieServerCsrfTokenRepository`) con rotación al crear la sesión, igual que el monolito. Mejora candidata: ligar el token a la sesión del gateway (valor en `WebSession` + cookie para la SPA), para que un par cookie+cabecera viejo deje de servir.
- (Seguridad, S1) Log de acceso: si se activa el del ingress o el de Reactor Netty, excluir `/api/auth/**` y `/oauth2/**` (llevan usuario, código o parámetros de la autorización). Hoy el gateway no tiene log de acceso.
- (Seguridad, S1) Rate limit de `/api/auth/**` en ventanas fijas de 1 min en Redis (por IP 60, por usuario 10, configurables). Si Redis cae, deja pasar (falla abierto) y quedan los topes de `seguridad` (bloqueo 5/15, 5 códigos/hora).
- (Seguridad, S1) Tokens de `oauth2_authorization` (refresh y access) quedan en claro en la base, como guarda Spring Authorization Server: backups cifrados y acceso restringido al esquema `seguridad`.
- (Seguridad, S1) Migración de datos: tras insertar los usuarios con sus id del monolito, avanzar la identidad (`setval`, comentado en V1 de seguridad). Cliente `gateway` fuera de local con secreto `{bcrypt}` (el `{noop}` solo se reconoce en local y pruebas).
