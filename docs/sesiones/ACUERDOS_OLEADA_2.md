# Acuerdos de la oleada 2 (integradora, 2026-11-06)

Leer junto con `docs/decisiones/` (ADR-001..014) y `librerias/comun/README.md`. Lo de aquí precisa los prompts de las sesiones.

## Un solo servicio `visitas`, dos sesiones

- **VISITAS A** es dueña del esqueleto: crea `servicios/visitas` (pom, aplicación, configuración, `contratos/visitas.yaml`, primeras migraciones). Puede agregar en su rama la línea `<module>servicios/visitas</module>` al `pom.xml` padre; es la única línea del pom padre que puede tocar. Cualquier otra versión o plugin se pide a la integradora.
- **VISITAS B** abre su sesión ya, pero **no escribe código del servicio hasta que VA1 esté en `develop`**. Mientras tanto: lee el monolito (explorador-codigo), inventaría pantallas, reglas y e2e, y manda su plan.
- **Paquetes**: A = `catalogos`, `maestros`, `agenda`. B = `ejecucion`, `hallazgos`, `opl`, `tablero` y, además, **`exportacion`** (actas, firmas, soportes, ZIP; ADR-002 la pone en `visitas` y la usa `hallazgos`).
- Paquetes Java separados por área (`co.com.agsamericas.digsa.visitas.<area>`) para no pisarse. Lo compartido del servicio (configuración, seguridad, outbox) es de A; B pide cambios por la integradora.

### Frontera precisada con el plan de VISITAS A (aprobado 2026-11-06)

- **Prórrogas** es de `ejecucion` (`ServicioProrrogas`, tabla `prorrogas`): pasa a **VISITAS B**.
- **Homologación** es de analítica (`/api/homologacion/**`, 26 rutas): **se queda en el monolito** (ADR-008, ya enrutada en el gateway).
- **Descargas del día** es una pestaña de Reportes (`motorreportes`): pasa a la sesión **reportes**.
- **VISITAS A es dueña del esquema compartido**: tabla `visitas` completa (también las columnas que escribe ejecución), equipo, reparto, historial, notificaciones y el checklist `chk_*` con su semilla v6. B agrega columnas o tablas suyas con migraciones de su rango, sin alterar las de A sin pedirlo por la integradora.
- A expone a B una **API Java interna** del mismo servicio (`RepositorioVisitas`, seguimiento al plan, `plazos()`, alcance, destinatarios), documentada en `servicios/visitas/README.md`.

### Decisiones técnicas para VISITAS A (D1–D7)

- **D1 · Notificación previa** (síncrona; su resultado decide si la visita queda `notificada`): igual que el D2 de seguridad, un **puerto con adaptador SMTP provisional a Mailpit**, con paridad exacta, registro y contenido guardados 1 año, destinatarios ficticios `@visitas-a.prueba.local`. El definitivo **no** es un evento: es una **llamada HTTP síncrona** a `notificaciones` (`client_credentials`, alcance `notificaciones:enviar`, Resilience4j), porque es un comando con respuesta inmediata (ADR-006). Excepción explícita a «nunca se envía correo desde visitas», solo para este adaptador provisional, que se retira cuando exista `notificaciones`.
- **D2 · Copia de usuarios**: el definitivo es el evento `usuario.*` de seguridad (S2). Hasta que llegue: endpoint `/interno/pruebas/usuarios` **solo con el perfil `local`/prueba** (no existe en otro perfil, con prueba de que no existe) para que las e2e siembren la copia.
- **D3 · `Almacen`**: lo escribe VISITAS A en `librerias/comun` (interfaz + adaptador de disco por volumen), en commits «comun:» compatibles (como hizo seguridad con el convertidor). Integridad con SHA-256 como `ArchivoIntegridadFallida` del monolito.
- **D4 · Gateway**: VISITAS A agrega en su rama las rutas `/api/{catalogos,maestros,agenda,calendario,mis-visitas,solicitudes,propuesta}/**` → `LUME_VISITAS_URL` con `TokenRelay`, `RemoveRequestHeader=Cookie` y `RemoveResponseHeader=Set-Cookie`, y `LUME_VISITAS_URL` en `generar-env.sh`; con una prueba en `GatewayTest`.
- **D5 · Carga masiva de maestros**: **dentro del alcance** (paridad), como fase **VA2b** después de VA2. Los maestros los carga `visitas`; la creación de usuarios del archivo va por una API interna de seguridad (`/interno/usuarios/importar`, alcance `seguridad:importar`), que se le pide a seguridad.
- **D6 · Parámetros**: en `visitas` solo las claves de visitas; las de presupuesto se quedan en el monolito y las de bandeja/alertas pasan a `notificaciones`. La pestaña muestra menos claves: diferencia anotada en el contrato.
- **D7 · e2e**: contra el **gateway y los servicios reales** de la sesión (seguridad real incluida); el mock de `frontend/mock` no se amplía para visitas.

## Sesión REPORTES Y NOTIFICACIONES (abierta 2026-11-06): lo ya decidido que la afecta

Son **dos servicios** (`reportes` y `notificaciones`, ADR-002), cada uno con su esquema, sus dos roles de BD y su usuario de RabbitMQ.

- **Alcance precisado frente al prompt:**
  - `exportacion` y la pantalla **Exportación** **no** son de esta sesión: van a `visitas` (VISITAS B; ADR-002 y decisión del usuario D-N2: exportación completa solo de visitas).
  - **Sí** son de esta sesión: **alertas** (`/api/alertas`, pantalla Alertas, `alertas.spec`; ADR-002 y D-T1 de visitas-b) y la pestaña **Descargas del día** de Reportes (motorreportes; frontera de visitas-a).
  - Reportes de presupuesto o analítica: se quedan en el monolito (ADR-008), marcados en docs.
- **Contratos ya acordados con visitas** (consultar `contratos/eventos/visitas.yaml` y `ACUERDOS` de visitas):
  - **Notificación previa de una visita**: llamada **HTTP síncrona** a `notificaciones` (`client_credentials`, alcance `notificaciones:enviar`, Resilience4j), porque su resultado decide si la visita queda `notificada`. Visitas lo resuelve hoy con un adaptador SMTP provisional que se retira cuando exista `notificaciones`.
  - **Correo del acta** (D-N1): la visita se cierra al generar el acta; visitas publica `aviso.acta_generada` y `notificaciones` responde con el evento **`envio.resultado`** `{referencia, estado enviado|agotado, intentos, ultimoError?}`; la referencia es **`acta:{id}:{envio}`** (envio = 1, 2… en cada reenvío) y `notificaciones` la devuelve **tal cual la recibió**, para que visitas descarte el resultado atrasado de un intento anterior en `notificaciones.eventos` (visitas lo consume en `visitas.envios`). Reintentos automáticos y alerta al administrador si se agotan.
  - **Plantillas** de visitas (`PlantillasAgenda`, `PlantillasEjecucion`, `PlantillasOpl`): viven en `notificaciones`; visitas manda clave + variables.
  - **Secretos fuera de RabbitMQ** (T1 de visitas-b): `aviso.acta_generada` lleva solo `{referencia "acta:{id}", plantilla, variables NO secretas, destinatarios}`. Lo secreto (el enlace del OPL con su token) y el PDF adjunto los pide `notificaciones` a `visitas` por HTTP con token de máquina: `GET /interno/avisos/acta/{id}` (alcance `visitas:avisos`, aud=visitas). El enlace se guarda **oculto** en el registro de correos, como en el monolito. Mismo patrón para cualquier aviso con secretos.
  - **Reenviar acta** (decisión del usuario 2026-11-06): si el envío del acta se agota, la visita cerrada muestra «Reenviar acta» (coordinador y líder): mismo PDF (misma huella) a los destinatarios actuales, sin reabrir; `POST /api/visitas/{id}/acta/reenviar` en visitas, que vuelve a publicar el aviso.
  - **Alertas**: visitas publica `alerta.*` (`alerta.intervencion_inmediata`, `plan_opl_vencido`, `tarea_diaria_fallida`, `integridad_archivo`).
  - **Reportes de visitas** (`motorreportes`, `dashboard_*`): sobre una **proyección** alimentada por `visita.*` y `hallazgo.*` (ADR-002), con carga inicial por API; nunca leyendo la base de visitas.
- **RabbitMQ** (matriz del ADR-006, ya aplicada): `notificaciones` lee `alerta.*` y `aviso.*` de `visitas.eventos`; `reportes` lee `usuario.*`, `cuenta.*` de `seguridad.eventos` y `visita.*`, `hallazgo.*` de `visitas.eventos`; `seguridad` lee `auditoria.*` de ambos. Una clave nueva se pide a la integradora.
- **Retención**: el registro **y** el contenido de cada correo se guardan 1 año, con los secretos (códigos, enlaces) enmascarados en lo guardado (ADR-004). El rol de la app no borra ese registro: purga por función `SECURITY DEFINER` (patrón de `seguridad`, V4).
- **Abierto para su plan**: el **teléfono** para el SMS de alertas críticas no viaja en `usuario.*` (minimización); proponer cómo obtenerlo (p. ej. un `/interno` de seguridad con alcance propio) y pedirlo por la integradora.
- Correo local: el Mailpit ya instalado, destinatarios ficticios `@reportes.prueba.local`.

## Flyway (ADR-005)

- **VISITAS A: V1–V199. VISITAS B: V200–V399.** Nadie usa números del otro rango.
- `spring.flyway.out-of-order=true` en el servicio `visitas` (las dos ramas migran el mismo esquema en paralelo). Cada migración es independiente del orden entre rangos: si una de B necesita una tabla de A, la tabla debe estar ya en `develop`.
- **El CI lo verifica** (`infra/ci/rangos-flyway.sh`): sin números repetidos ni fuera de V1–V399, y cada rama `visitas-a`/`visitas-b` solo agrega migraciones de su rango.
- **Datos de catálogo en `V`, no solo en `R__`** (M3 de la revisión de BD de A): Flyway corre las repetibles al final, así que en una base nueva una V2xx de B no vería filas cargadas por `R__catalogos`. Las filas de las que pueda depender otra migración (p. ej. `par_tipos_visita`, `par_parametros`) van en una V de A; `R__catalogos` queda para actualizaciones idempotentes. Ninguna migración de B depende de datos que solo cargue una `R__`.
- En el perfil **local**, `spring.flyway.ignore-migration-patterns: "*:missing"` permite cambiar de rama sin recrear la base; en pruebas y en cualquier otro entorno no se usa.
- Modelo de dos roles (`visitas_ddl` para Flyway, `visitas` para la app) con `GRANT` explícito por tabla con `${rol_app}`. Las pruebas usan `infra/postgres/init/01-esquemas.sh` (ver el patrón de `servicios/seguridad`).

## Lo que ya existe y se reutiliza

- `librerias/comun`: errores RFC 7807 con los textos del monolito, servidor de recursos JWT (`/api/**` solo con token de persona; `/interno/**` con token de máquina y alcance `<servicio>:<operación>`, `aud=<servicio>`), auditoría, outbox (cuando llegue S2 de seguridad) y `UsuarioActual`.
- Usuarios y perfiles: **copia local alimentada por los eventos `usuario.*` de seguridad** (S2). Hasta que lleguen, semilla ficticia solo en el perfil `local` y en pruebas.
- Correos y alertas: **nunca se envían desde `visitas`**; se publican eventos para `notificaciones` (oleada 2, sesión reportes).
- Frontend: las pantallas van en `frontend/src/app/paginas/visitas/` según `frontend/README.md` («Agregar pantallas»), con el design system de `ui/` y el registro de rutas `rutas-modulos.ts` (cada sesión edita solo su bloque).
- Puertos: los de su desfase en `infra/.env` (ya generado; no regenerarlo).

## Datos personales en logs (regla común, 2026-11-06; aplica a todas las sesiones)

- Los **modelos generados desde `contratos/`** (`*.api.modelo.*`) y los cuerpos de petición o respuesta **nunca** se escriben en logs, en mensajes de excepción ni en la bitácora: su `toString()` generado imprime todos los campos, incluidos documento, correo, teléfono y nombre. Para trazar, se usan identificadores (`uid`, `id` de visita, `fila`).
- Lo mismo para los registros propios con datos personales: su `toString()` omite documento, correo y teléfono (como `UsuarioCopia` y `Foto` de comun).
- La revisión de seguridad de cada entrega lo verifica (búsqueda de `log.*(` con modelos o cuerpos).

## Entregas

Como en la oleada 1: plan aprobado antes de programar; por fase, hash + pruebas por módulo (contadas desde los `TEST-*.xml`) + `npm audit` completo si toca frontend + dudas. La integradora revisa con revisor-codigo y seguridad, verifica en limpio y hace el merge.
