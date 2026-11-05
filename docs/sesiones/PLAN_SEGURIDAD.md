# Plan de la sesión SEGURIDAD (S1..S5)

- Sesión: seguridad · worktree `dev-digsa-arqui-seguridad` · rama `seguridad` · desfase +100 (gateway 9100, seguridad 9201, PG 5441, Redis 6381, RabbitMQ 5772).
- Base: `develop` @ 2d9f0fe (ADR-001..012 y ADR-014).
- Origen leído (explorador-codigo, solo lectura): `digsa-java/backend/app` paquetes `identidad`, `auditoria`, `plataforma/RegistroBitacora|FiltroBitacora`; `backend/reglas`; migraciones V1, V2, V3, V5, V8, V30, V31, V32; pruebas de integración de `identidad` y `auditoria`; e2e en `e2e/pruebas/` (no en `frontend/e2e`).
- Método por unidad: pruebas primero (qa-tdd) → código → revisor-java (+ revisor-bd-postgresql si hay SQL) → seguridad → qa cierra la unidad.

## Hallazgos del monolito que condicionan el plan

1. **Rutas reales de `/api/auth`** (`ControladorIngreso`): `GET inicio`, `POST ingreso`, `POST codigo`, `POST codigo/reenviar`, `POST cambiar-clave`, `POST olvide`, `GET clave?tkn=`, `POST clave`. Las rutas `olvide-clave` y `definir-clave` son de **pantallas**, no de la API. Como ADR-014 dice «las mismas rutas del monolito», el contrato usa las del monolito (ver duda D1).
2. **Formato de error**: el monolito responde `{mensaje, campo?, motivo?}` (no RFC 7807). `comun/Problemas` ya emite RFC 7807 **más** `mensaje`, `campo` y `motivo` al mismo nivel, así que es compatible con lo que leen las pantallas y los e2e.
3. **Retención de la bitácora**: `log_transacciones` es permanente (el rol de aplicación solo tiene `SELECT, INSERT`; particiones mensuales 2026-10..2030-12). V92 es retención de la bandeja de correos (365 días), no de auditoría.
4. **Sin historial de claves**: solo se compara con la clave actual. Política: 12..64 caracteres (≤72 bytes), sin ñ ni tildes, mayúsculas, minúsculas, número y especial (`reglas/Claves`, ya copiado).
5. **Pantallas y matriz de perfiles** viven en `reglas/Pantallas` y `reglas/Roles` (ya copiados): no hay tabla de pantallas.
6. **Constantes**: MFA de 6 dígitos, SHA-256, 15 min, 5 intentos, 5 códigos/hora; bloqueo 5 fallos/15 min; clave inicial 72 h; vigencia de clave 30 días; enlace 60 min, 1 cada 5 min; inactividad 1..480 (por defecto 10, caché 30 s); BCrypt 12.

## S1 · Servidor de identidad + ingreso (ADR-004, ADR-014)

**Qué trae del monolito**: `ServicioIngreso`, `ServicioClaves` (olvide, definir, cambiar propia), `Captcha` (Turnstile), `ParametrosSeguridad`, `ControladorIngreso`, `ControladorSesion`, `ControladorCuenta`, el modelo de V1/V5/V30/V31 de usuarios, perfiles y MFA, la parte de sesión de `FiltroSesion` (motivos 401) y `PlantillasCorreo` (`mfa_codigo`, `clave_enlace`).

Unidades, en este orden:

- **S1.0 · Contrato primero** (se entrega antes del resto, para angular-base): `contratos/comun.yaml` (esquema `Problema` = RFC 7807 + `mensaje`, `campo`, `motivo`, si la integradora no lo ha creado) y `contratos/seguridad.yaml` parcial con `/api/auth/*` (con `continuar` en la respuesta final), `/api/sesion`, `/api/sesion/estado`, `/api/sesion/mantener` y `/api/cuenta/clave`. Mismos cuerpos, códigos y textos del monolito. `openapi-generator-maven-plugin` (versión fija, `interfaceOnly`) en `seguridad`. Lint con Redocly o Spectral, con versión fija (si la integradora ya lo tiene en el CI, uso el suyo).
- **S1.1 · Esquema** (Flyway V1.. en el esquema `seguridad`, citando el Vnn del monolito): `perfiles` (+`base_id`, secuencia de roles desde 100, `ck_perfiles_base`), `usuarios` (las mismas columnas e id, `sesion_huella`, `sesion_ultimo`, `clave_inicial`), `usuarios_perfiles`, `perfiles_pantallas`, `mfa_codigos`, `seg_parametros`, `sesiones` (sid, usuario, huella, inicio, último, cierre y motivo), las tablas JDBC de Spring Authorization Server (`oauth2_authorization`, `oauth2_registered_client`), `jwk` (clave privada cifrada con AES-GCM; la clave de cifrado viene del `.env`, y de Vault en el cierre) y Spring Session JDBC para `LUME_IDP` (varias réplicas). Semilla: los 10 perfiles base. Usuarios: solo ficticios, solo en el perfil `local` o en pruebas.
- **S1.2 · Ingreso de dos pasos**: `/api/auth/inicio|ingreso|codigo|codigo/reenviar|cambiar-clave` con anti-temporización (hash de relleno), mensaje genérico, bloqueo atómico 5/15, sesión única (409 `sesion_abierta`), MFA (hash, vencimiento, intentos, tope por hora, consumo atómico, 502 si el correo no sale) y antifijación de sesión. Al terminar, la sesión `LUME_IDP` queda autenticada y la respuesta trae `continuar` (la petición `/oauth2/authorize` guardada). `loginPage("/ingreso")`. Desaparece `/login` del andamiaje.
- **S1.3 · Claves**: `olvide` (respuesta siempre igual, asíncrono con cola acotada, 1 cada 5 min), `GET/POST clave` (un solo uso, tiempo constante), `POST /api/cuenta/clave` (cuenta como fallo y bloquea; 401 `bloqueado`; 409 por carrera). Política de `reglas/Claves`, vigencia de 30 días, clave inicial de 72 h.
- **S1.4 · Emisor y tokens**: `ClaimsToken` desde las tablas (`uid` = id del monolito, `perfiles` solo directos, `pantallas`, `asignados`, `alcance` estructurado y `sid`); `aud` por destinatario (bloqueante 3). JWK y autorizaciones en base, con rotación de claves (bloqueante 2, parte emisor). Access token de 5 min y refresh revocable (duda D3). `client_credentials` con un alcance por operación. Se borra todo lo marcado `ANDAMIAJE I1` (bloqueante 5): usuario en memoria, `uid` ficticio y formulario por defecto. El cliente `humo` queda solo en `local`.
- **S1.5 · Sesión en `seguridad`**: `/api/sesion` (menú y pantallas desde `reglas/Pantallas`), `/api/sesion/estado` (no renueva) y `/api/sesion/mantener` (GET, 405 en otro método). Endpoint interno `/interno/sesiones/{sid}` (`client_credentials`, alcance `seguridad:sesiones`) que devuelve si está vigente, el motivo de cierre con el mensaje del monolito (`inactivo`, `clave`, `cerrada`, `reemplazada`, `clave_vencida`, `cambio_clave`) y los minutos de inactividad. `POST /interno/sesiones/{sid}/actividad` (como máximo cada 30 s) y `POST /interno/sesiones/{sid}/cerrar`.
- **S1.6 · Gateway** (autorizado por ADR-014 §3):
  - Ruta `/api/auth/**` → `seguridad`: `permitAll`, CSRF del gateway, sin `TokenRelay`, solo la cookie `LUME_IDP` de ida y vuelta.
  - Límite de tasa con Redis por IP y por usuario. El usuario sale del cuerpo de `ingreso` y `olvide`, leído con un tope de 4 KB (bloqueante 4).
  - **Bloqueante 1**: `POST /bff/salir` → 204. El gateway revoca el refresh token (`/oauth2/revoke`), llama `/interno/sesiones/{sid}/cerrar` (`seguridad` cierra la fila `sesiones`, invalida la sesión `LUME_IDP` de ese usuario por índice de principal y borra sus autorizaciones) e invalida `LUME_SESION`. Así hay 204 sin que el navegador tenga que redirigir. El emisor sigue anunciando `end_session_endpoint` (RP-initiated logout) para otros clientes.
  - **Bloqueante 2**: `ClientAuthorizationException` u `OAuth2AuthorizationException` del `TokenRelay` → se invalida la sesión y se responde 401 `sin_sesion` (RFC 7807), nunca 500.
  - **Bloqueante 3, lado gateway**: en cada petición `/api/**`, `sid` contra `/interno/sesiones/{sid}` con caché de 30 s como máximo. La inactividad se mide en el gateway (último acceso en la sesión de Redis; `/api/sesion/estado` no lo toca) con los minutos que devuelve `seguridad`. El cierre responde 401 con el `motivo` y el texto del monolito.

**Pruebas que lo cubren**
- Unitarias: reglas (ya están) + servicios con reloj movible.
- Integración con Testcontainers (`postgres:16.15` con digest), portadas de `IngresoYSesionApiTest`, `CuentaYSeguridadApiTest` y `SesionesYClaveInicialApiTest` (parte de sesión), más `ValidacionArranqueYCaptchaTest`. Flujo OIDC completo authorize → `/ingreso` → `continuar` → code → token, claims y `aud` revisados.
- Gateway: `WebTestClient` con Redis de Testcontainers y un emisor simulado: salir 204 + revocación; refresh caducado → 401; CSRF 419 en `/api/auth/**`; límite de tasa 429; `sid` cerrado → 401 `reemplazada`.
- Humo `infra/humo.sh` ampliado: ingreso real con el código leído de Mailpit (filtrado por `@seguridad.prueba.local`).
- e2e de navegador: los de `sesion.spec.ts` dependen de la pantalla Angular (angular-base A3/A4). Los ejecuto contra su rama cuando esté.

## S2 · Administración de usuarios, roles, sesiones y bloqueo

**Qué trae**: `ServicioUsuarios`, `ServicioRoles`, `ControladorUsuarios` (usuarios, estado, restablecer, clave inicial, cerrar sesión, desbloquear, `/seguridad` TIME OUT, roles y nombres de perfiles), `CrearAdministrador` (ApplicationRunner) y todas las validaciones y textos del inventario §7.

- Analista = consulta nacional (`Alcance.Todo` con las pantallas de ANALISTA, sin AGENDA, SOLICITUDES ni administración).
- `UsuarioActual.alcance()` falla cerrado: un usuario sin alcance no ve datos (pendiente de I1, cambio en `librerias/comun`).
- **Eventos con outbox** (ADR-006): `usuario.creado|actualizado|estado`, `usuario.permisos_cambiados`, `sesion.cerrada`, `cuenta.bloqueada`, en el exchange `seguridad.eventos`, con el sobre común y la versión del agregado. Esquemas en `contratos/eventos/seguridad.yaml`.
- El gateway consume `sesion.cerrada` y `usuario.permisos_cambiados`: cierra la sesión o fuerza el refresco del token sin esperar a que venza.
- **Copia de puntos y regionales** (de `visitas`) para validar ESM y OPL regional: tablas copia + consumidor idempotente (`mensajes_procesados`). Mientras `visitas` no publique, la validación falla cerrado (ver duda D5).
- `/interno/usuarios/exportacion` (`client_credentials`) para la conciliación nocturna del monolito (S4) y para la carga inicial de `visitas` y `reportes`.

**Pruebas**: integración portada de `UsuariosYClavesApiTest`, `RolesApiTest`, `SesionesYClaveInicialApiTest` (administrador) y `CrearAdministradorTest`. Outbox con un RabbitMQ de Testcontainers (`rabbitmq:4.3.7-management` con digest), idempotencia y orden por versión.

## S3 · Auditoría y logs como servicio

**Qué trae**: `RegistroBitacora`, `FiltroBitacora`, `reglas/Bitacora` (lista blanca, secretos, cortes, cambios y enmascaramiento, Ley 1581), `RepositorioLogs`, `ControladorLogs` (consulta paginada por llave, exportación xlsx/csv con encabezado, envío por correo) y `ProveedorReporteLogs`.

- `librerias/comun`: `PublicadorAuditoria` real (outbox, en la misma transacción) y el filtro equivalente a `FiltroBitacora` (una fila por petición con el código final; nunca lee el cuerpo de `/api/auth/**` ni de `/api/cuenta/**`). El outbox y su despachador (`FOR UPDATE SKIP LOCKED`) quedan en `comun` para todos los servicios (ADR-006).
- `seguridad` consume la auditoría de todos los exchanges (`*.eventos`, clave `auditoria.#`), valida que `origen` coincida con el exchange y escribe en `log_transacciones` (particionada por mes, solo `SELECT, INSERT` para el rol de aplicación, sin purga).
- `/api/logs`, `/api/logs/opciones`, `/api/logs/exportar` y `/api/logs/enviar`, con los mismos filtros, topes (92 días con texto, 200 000 filas) y textos. Para xlsx: Apache POI con versión fija; lo justifico en la entrega.
- API interna para el reporte `logs_auditoria` de `reportes` (ADR-002: `reportes` lo pide por API).

**Pruebas**: `LogsApiTest` y `BitacoraTest` portados, consumidor idempotente, `origen` falsificado rechazado, exportación por lotes sin agotar el pool.

## S4 · Convivencia con digsa-java (parche + pasos, sin commit en digsa-java)

- Trabajo sobre un **clon desechable** de digsa-java en mi scratchpad (`git clone --branch develop`), así el original no se toca. Monolito de prueba en el puerto 9150, contra mi gateway 9100.
- Parche (ADR-008):
  - Cadena *resource server* propia, sobre Boot 3.5 y sin `librerias/comun`, solo para `/api/analitica/**` y `/api/presupuesto/**`.
  - JWT → `UsuarioSesion(uid, usuario, nombre)` con `PERFIL_*` solo de `perfiles`, más `Acceso(alcance, pantallas, asignados)`.
  - `/api/auth/**` y la cadena de sesión propia se desactivan para los usuarios migrados.
  - Sincronizador RabbitMQ de `usuario.*` sobre la copia de `usuarios`, `usuarios_perfiles` y `perfiles` con los mismos id.
  - Conciliación nocturna contra `/interno/usuarios/exportacion`.
- Inventario de las llamadas de la interfaz React de analítica y presupuesto a rutas que ahora son de los servicios nuevos, cruzado con los contratos.
- Entrega en `docs/convivencia/digsa-java-S4.patch` + `docs/convivencia/PASOS_S4.md`, con las pruebas del monolito parcheado (su suite + e2e de analítica).

## S5 · Contratos completos

`contratos/seguridad.yaml` completo (auth, sesión, cuenta, usuarios, roles, perfiles, seguridad, logs e internos) y `contratos/eventos/seguridad.yaml`. Lint y `openapi-diff` contra `develop`. Los controladores implementan las interfaces generadas: si el código se aparta del contrato, no compila. El contrato parcial ya sale en S1.0 y crece en cada fase.

## Dudas para la integradora

- **D1 · Rutas de recuperación.** ADR-014 nombra `olvide-clave` y `definir-clave` como API, pero el monolito usa `POST /api/auth/olvide`, `GET /api/auth/clave?tkn=` y `POST /api/auth/clave` (`olvide-clave` y `definir-clave` son las pantallas). Propongo las del monolito, como dice el propio ADR («mismas rutas»), y agregar `GET /api/auth/inicio` (Turnstile + CSRF). ¿De acuerdo? Afecta al mock de angular-base.
- **D2 · Correo del código MFA y de los enlaces antes de que exista `notificaciones`** (sesión reportes, oleada 2). Propongo un puerto `CorreoSeguridad` con dos adaptadores: (a) el definitivo, HTTP a `notificaciones` (`client_credentials`, alcance `notificaciones:enviar_secreto`, contrato a acordar con reportes por medio de la integradora); (b) uno provisional, SMTP directo a Mailpit, que guarda el registro y el contenido **con el secreto enmascarado** en `seguridad.correos_registro`, con purga a 365 días. El (b) se retira cuando llegue R1.
- **D3 · Refresh token.** ADR-004 dice «rotativo»; I1 dejó `reuse-refresh-tokens: true` para el cliente confidencial. Propongo mantenerlo reutilizable: nunca sale del gateway, es revocable y vence a las 8 h o al cerrar sesión. Rotar exigiría serializar el refresco por sesión en el gateway reactivo. Si se exige rotación, lo hago con un candado por sesión en Redis.
- **D4 · Pantallas Angular de administración.** Usuarios, Roles adicionales, Logs y «Mi contraseña» no están asignadas a nadie (angular-base solo tiene ingreso, MFA, recuperación y cambio de clave). Mi lista de agentes no incluye revisor-angular. Propongo que yo entregue backend y contrato, y que porte los e2e `cuenta-y-seguridad`, `usuarios-y-logs`, `logs-cambios` y `roles` como pruebas de API de integración. ¿Quién hace esas pantallas y sus e2e de navegador?
- **D5 · Copia de puntos y regionales.** Hasta que `visitas` publique sus eventos, crear usuarios ESM u OPL regional falla cerrado (422). ¿Lo aceptamos así, o cargo una semilla ficticia solo en `local`?
- **D6 · `librerias/comun`.** S2 y S3 tocan `comun` (alcance que falla cerrado, outbox, publicador de auditoría y filtro de bitácora). ¿Los hago en mi rama para que los revises en el merge, o prefieres hacerlos tú?
- **D7 · Dependencias nuevas** (las justifico en cada entrega): `spring-boot-starter-jdbc`/`data-jdbc`, `flyway-database-postgresql`, `spring-session-jdbc`, `spring-boot-starter-mail` (solo para el adaptador provisional de D2), `spring-boot-starter-amqp`, Testcontainers PostgreSQL y RabbitMQ, `openapi-generator-maven-plugin` y, en S3, Apache POI.

## Decisiones de la integradora (2026-11-05, plan aprobado)

- **D1** Rutas exactas del monolito: `GET inicio`, `POST ingreso`, `POST codigo`, `POST codigo/reenviar`, `POST cambiar-clave`, `POST olvide`, `GET clave?tkn=`, `POST clave` (ADR-014 corregido).
- **D2** Puerto `CorreoSeguridad` con dos adaptadores. El provisional (SMTP a Mailpit 127.0.0.1:1025) debe cumplir cuatro condiciones:
  - guarda el registro y el contenido con el secreto enmascarado, y los purga a los 365 días;
  - nunca escribe el secreto en los logs;
  - solo envía a destinatarios ficticios `@seguridad.prueba.local`;
  - se retira cuando llegue `notificaciones` (R1).

  El contrato HTTP con `notificaciones` lo acuerda la integradora con reportes.
- **D3** `reuse-refresh-tokens` para el cliente confidencial `gateway`, sin rotación por ahora.
- **D4** Las pantallas Angular de Usuarios, Roles y Logs pasan a angular-base como A5; «Mi contraseña» ya está en su A3. Seguridad entrega el backend, el contrato con los mismos textos y códigos, y los e2e del monolito portados como pruebas de API.
- **D5** Fuera de `local` falla cerrado. La semilla ficticia de puntos y regionales existe solo en el perfil `local` y en las pruebas.
- **D6** Los cambios a `librerias/comun` se hacen en la rama `seguridad`, con estas condiciones:
  - van en commits separados que empiezan por «comun:»;
  - la prueba de `hola` sigue en verde;
  - la cadena de recurso se sigue aplicando siempre y de última;
  - el outbox queda documentado en `librerias/comun/README.md`.
- **D7** Las dependencias nuevas van por el BOM de Boot o con versión fija en el pom padre; esas versiones se piden a la integradora. Apache POI se aprueba en S3 con la misma versión del monolito, salvo que tenga una CVE abierta.
