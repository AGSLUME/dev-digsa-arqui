# Plan de la sesión VISITAS A (VA1..VA3)

- Sesión: visitas-a · worktree `dev-digsa-arqui-visitas-a` · rama `visitas-a` · desfase +300 (gateway 9300, seguridad 9401, visitas 9402, Angular 4230, PG 5443, RabbitMQ 5972, Redis 6383).
- Base: `develop` @ 54fe207 (ADR-001..014, `ACUERDOS_OLEADA_2.md`).
- Origen leído (explorador-codigo, solo lectura, `digsa-java` @ 1ff82de): paquetes `catalogos`, `maestros` (incluida `maestros/carga`), `agenda` y lo que usan de `plataforma`, `identidad` y `backend/reglas`; migraciones V3, V4, V6, V7, V8, V11, V12, V13, V15 y `R__catalogos.sql`; pruebas `CatalogosApiTest`, `MaestrosApiTest`, `CargaMaestrosTest`, `AgendaApiTest`, `PropuestaApiTest`, `SolicitudesApiTest`, `SolicitudesEstresApiTest` y `PlantillasAgendaFabricaTest`; pantallas React y e2e de `e2e/pruebas`.
- Método por unidad: pruebas primero (qa-tdd) → código → revisor-java (+ revisor-bd-postgresql si hay SQL, revisor-angular en VA3) → qa cierra la unidad.

## Hallazgos del monolito que cambian el encargo

1. **Tres pantallas del encargo no son de mis paquetes:**
   - **Prórrogas** está en `ejecucion` (`ServicioProrrogas`, `ControladorSeguimiento.java:48-56`, tabla `prorrogas` de V13) y depende del acta y del seguimiento. Le corresponde a VISITAS B.
   - **Homologación** es de analítica (`analitica/homologacion/HomologacionApi`, `/api/homologacion/*`), que se queda en el monolito (ADR-008).
   - **Descargas del día** no es una pantalla: es una pestaña de Reportes (`/api/reportes/descargas`, paquete `motorreportes`). Le corresponde a la sesión reportes.
2. **`agenda` contiene piezas que usa ejecución:**
   - `iniciar` (Mis visitas).
   - `programarSeguimiento`, `cancelarSeguimientoPendiente` y `notificarSeguimiento`.
   - `RepositorioVisitas`, que importan `ejecucion`, `opl`, `tablero`, `hallazgos` y `motorreportes`.
   - `ServicioConsultaAgenda.plazos()` y `condicion()` (el SQL de alcance).
   - `ServicioNotificacion.calcular()`, que el acta usa para sus destinatarios.
3. **Mis visitas, Calendario, Solicitudes, Propuesta y Seguimiento (`/seguimiento`, pantalla AGENDA) sí son de `agenda`**, y por tanto míos.
4. **El checklist (`chk_*`) no tiene paquete.** Lo crean V3 y V4 y lo leen agenda (programar exige una versión vigente), ejecución, tablero y reportes. No tiene API de administración.
5. **La notificación previa es síncrona y su resultado decide el estado.** La visita pasa a `notificada` solo si todos los destinatarios obligatorios aceptan y la «foto» (estado, fecha, hora, equipo) no cambió mientras salía el correo (`ServicioNotificacion.java:203-333`). Los otros tres correos (avisos de solicitudes) ya van por la bandeja de salida.
6. **Cruces con `usuarios`:**
   - `puntos.auditor_asignado` y `auditores.usuario_id` son FK a `usuarios`.
   - Contar usos de una fuerza, de una regional o de un punto mira `usuarios.alcance_*`.
   - `auditores()` hace JOIN con `usuarios_perfiles`.
   - El alcance de calendario y solicitudes sale de la sesión.
7. **La carga masiva (`maestros/carga`) es una CLI sin pantalla ni e2e.** Crea usuarios, perfiles y enlaces de bienvenida (identidad), además de puntos y auditores (maestros).
8. **`par_parametros` mezcla claves de agenda, seguimiento, riesgo, presupuesto, bandeja y alertas**, y la escriben tres módulos.
9. **Los e2e siembran datos por API:** los e2e siembran por API (`POST /api/usuarios`, `PUT /api/maestros/auditores/{id}`, `POST /api/maestros/puntos`, `POST /api/agenda`, `POST /api/mis-visitas/{id}/iniciar`). Esos contratos deben quedar idénticos, porque los usan también los e2e de B (formulario-acta, riesgo, mapas, opl, seguimiento).

## Propuesta de frontera (para aprobar)

| Tema | Propuesta |
|---|---|
| Prórrogas (pantalla, API, tabla) | VISITAS B, con `ejecucion`. |
| Homologación | Fuera de la oleada: monolito (ADR-008). |
| Descargas del día | Sesión reportes. |
| Tabla `visitas` completa | A, incluidas las columnas que escribe ejecución (`reaperturas`, `origen_id`, `seguimiento_n`, `checklist_version`, `iniciada`; V6, V8 y V13). |
| Tablas de equipo y trazas de la visita | A: `visitas_componentes`, `visitas_auditores`, `visitas_reparto`, `visitas_historial` (la escriben todos; el rol de la app solo tiene SELECT e INSERT) y `visitas_notificaciones`. |
| Repositorio y operaciones para B | `RepositorioVisitas` (A) es la API Java interna para B, con la convención del monolito de bloquear primero la visita. A también expone `ServicioAgenda.programarSeguimiento/cancelarSeguimientoPendiente/notificarSeguimiento`, `plazos()`, la condición de alcance y el cálculo de destinatarios. |
| Cerrar y reabrir la visita | B, con `RepositorioVisitas.cambiarEstado` y la máquina de `reglas/EstadosVisita`. |
| Checklist (`chk_*`) | Esquema y semilla v6 en A, porque agenda lo exige para programar. La lógica del formulario es de B. |
| `obligaciones_contrato` | A (maestros). `visitas_obligaciones` es de B. El bloqueo «ya se calificó en visitas» se resuelve con un puerto `UsoObligaciones`: A trae una implementación que responde «no» y B entrega la real. |
| `par_parametros` | Solo las claves de visitas: agenda, propuesta, seguimiento, riesgo y `correo_digsa`. `presupuesto_*` se queda en el monolito. `bandeja_retencion_dias` y `alertas_bloqueo_cuentas` pasan a `notificaciones`. Diferencia visible: la pestaña Parámetros de Catálogos deja de mostrar esas claves (D6). |
| Usuarios | Tabla local `usuarios_copia` (id del monolito, usuario, nombre, documento, correo, activo, perfiles, `alcance_fuerza/punto/regional`), sin FK desde `puntos` ni `auditores`: la integridad la valida la aplicación. La alimentan los eventos `usuario.*` de seguridad (S2), con carga inicial y conciliación por API. Mientras tanto, semilla ficticia solo en `local` y en pruebas. |
| Eventos de visitas | `punto.*`, `regional.*` y `fuerza.*`, para la copia de seguridad (ADR-002). Más adelante, `visita.*` para la proyección de reportes. Esquemas en `contratos/eventos/visitas.yaml`, con el outbox de `comun` cuando exista. |
| Carga masiva | Fuera de VA1–VA3 (D5). |

## VA1 · Servicio, esquema, catálogos y maestros

- **VA1.0 · Esqueleto y contrato:**
  - `servicios/visitas`: pom igual al de seguridad sin el servidor de autorización, `VisitasAplicacion`, `application.yml` y `application-local.yml` con los dos roles, `out-of-order=true`, `placeholders.rol_app`, `audiences: visitas` y el puerto `VISITAS_PUERTO`.
  - En el pom padre, solo la línea `<module>servicios/visitas</module>`.
  - `contratos/visitas.yaml` 0.1.0 con `/api/catalogos/**` y `/api/maestros/**`: mismas rutas, cuerpos, códigos y textos del monolito, y `Problema` de `comun.yaml`.
  - Soporte de pruebas: `ContenedoresPrueba` con `infra/postgres/init/01-esquemas.sh`, `PruebaIntegracion` y `RelojMovible`, y la prueba de esquema (GRANT mínimos y roles).
- **VA1.1 · Esquema** (V1–V9 de A; cada archivo cita su Vnn del monolito):
  - `V1__catalogos.sql`: departamentos, municipios, unidades, fuerzas, regionales, modalidades, tipos de visita, parámetros, festivos, `catalogos` y `catalogo_valores` (de V3).
  - `V2__usuarios_copia.sql`.
  - `V3__maestros.sql`: `opl`, `puntos` (con `regional_opl` y `fuente`), `auditores` y `obligaciones_contrato` (de V3, V6, V8:20 y V12:9-17). Sin `centralizador_id`, que es de presupuesto y se queda en el monolito.
  - `V4__checklist.sql` (de V3:164-220) y `V5__checklist_v6.sql` (de V4).
  - `V6__datos_iniciales.sql` (de V7): fuerzas, catálogos, regionales y los 185 puntos del anexo del OPL. Son datos públicos del anexo, no personales.
  - `R__catalogos.sql`: geografía DANE, unidades, modalidades, 9 tipos de visita y las claves de parámetros de visitas, con la misma regla de conservar el valor editado.
  - GRANT de V3:223-235 y V12:218 con `${rol_app}`.
- **VA1.2 · API de catálogos**: los 11 endpoints, con validaciones y textos literales. `GET /municipios` exige solo autenticación; el resto, la pantalla CATALOGOS. Auditoría con `PublicadorAuditoria` y los tipos de `reglas/Bitacora`. Se veta que un catálogo se llame `fuerzas` o `regionales`.
- **VA1.3 · API de maestros**: puntos (validación acumulada «Revise el punto: …»), estado, auditores (rol técnico y municipio base, contra `usuarios_copia`), OPL (un solo activo, ahora con índice único parcial) y obligaciones. Contacto y correos van a la bitácora como «modificado», sin el valor.
- **VA1.4 · Copia de usuarios y eventos:**
  - Lectura de `usuarios_copia` detrás de un puerto `DirectorioUsuarios`: auditores activos, conteos por alcance y perfil.
  - Semilla ficticia `@visitas-a.prueba.local` solo en `local` y en pruebas.
  - El consumidor `usuario.*` con idempotencia y orden por versión (ADR-006) entra cuando S2 y el outbox estén en `develop`; si no llegan antes del cierre de VA1, queda como pendiente con fecha.
- **Pruebas:** port de `CatalogosApiTest` (datos iniciales: 1122 municipios y 185 puntos, fuerza en vivo, validaciones, valores, regionales y parámetros, solo coordinador) y `MaestrosApiTest`, más lo que el monolito no cubre: obligaciones, `PUT /puntos/{id}/estado` con usuarios ESM activos, `correo_digsa`, y la prueba de esquema y GRANT.

## VA2 · Agenda: visitas, solicitudes, propuesta, calendario y mis visitas

- **VA2.0 · Contrato** 0.2.0: `/api/agenda/**`, `/api/calendario/**`, `/api/mis-visitas/**`, `/api/solicitudes/**` y `/api/propuesta/**`, con los textos de §2 del inventario.
- **VA2.1 · Esquema** (V10–V19):
  - `V10__visitas.sql` (de V6, V8 y V13): `visitas`, componentes, auditores, reparto, historial (solo SELECT e INSERT) y notificaciones, con los CHECK, índices y el UNIQUE de origen vigente.
  - `V11__solicitudes.sql` (de V11).
  - `V12__propuesta.sql` (de V15), con el trigger de solo borrador.
- **VA2.2 · Visitas:**
  - Operaciones: programar, editar, reprogramar, cancelar, reasignar, mover reparto y detalle.
  - Listados: agenda (≤2000 filas, con alertas de plazo) y `/seguimiento`.
  - Reglas tomadas tal cual de `reglas/` (`EstadosVisita`, `Plazos`, `Reparto`, `Fechas` y `Festivos`), más `par_festivos`.
  - Concurrencia como en el monolito: `FOR UPDATE` sobre la visita y UPDATE condicionado al estado. Además, propongo tomar en `programar` el mismo advisory lock por punto que ya toma la propuesta, para cerrar la duplicación que se ve en `ServicioPropuesta.java:671`.
- **VA2.3 · Notificación previa**: según la decisión D1 (abajo). En cualquier opción, `visitas_notificaciones` guarda destinatarios, asunto y cuerpo, y se conservan las reglas de destinatarios obligatorios, `faltan`, el tope de 50 en automático y de 1 a 15 en manual, y la foto.
- **VA2.4 · Mis visitas y calendario:**
  - Mis visitas: listado, detalle e `iniciar` (idempotente; fija la versión del checklist y rehace el reparto).
  - Calendario: con el alcance del token (`UsuarioActual`/claims `alcance`), la regla «sin necesidad pendiente» para OPL, ESM y regional, y sin equipo para los perfiles de consulta.
- **VA2.5 · Solicitudes**: registrar con el escrito PDF (25 MB, PDF detectado por contenido, sha256), anular y descargar el escrito (`nosniff`, `no-store`, 404 fuera de alcance). El almacenamiento va detrás de la interfaz `Almacen` (D3). Los avisos `solicitud_nueva`, `solicitud_agendada` y `solicitud_cancelada` salen como eventos para `notificaciones`, en la misma transacción.
- **VA2.6 · Propuesta**: generar, ajustar, liberar, recalcular, descartar y confirmar (una transacción y un advisory lock por fila; se puede continuar si se corta) y la cobertura, con `reglas/PropuestaAgenda`.
- **VA2.7 · API interna para B**: `RepositorioVisitas`, el seguimiento al plan, `plazos()`, la condición de alcance y los destinatarios, documentados en `servicios/visitas/README.md`.
- **Pruebas:**
  - Port de `AgendaApiTest`, `PropuestaApiTest`, `SolicitudesApiTest`, `SolicitudesEstresApiTest` y `PlantillasAgendaFabricaTest`.
  - Las de concurrencia que el monolito tiene pendientes (`dudas_pendientes.md` 18.15): dos coordinadores cancelan a la vez, iniciar frente a mover, notificar frente a reprogramar, y programar a mano frente a confirmar la propuesta.

## VA3 · Pantallas Angular y e2e

- **Pantallas** en `frontend/src/app/paginas/visitas/`, con las reglas de «Agregar pantallas»:
  - Catálogos (pestañas Fuerzas, Tipos de documento y demás catálogos, Regionales, Parámetros).
  - Maestros (Puntos, Auditores, OPL, Obligaciones del contrato).
  - Agenda, con el formulario de visita, el detalle de la visita en su parte de agenda y Seguimiento.
  - Calendario, Solicitudes (con el visor del escrito), Propuesta (tabla y tarjetas a 360 px) y Mis visitas.
  - Textos y `data-testid` idénticos a los del monolito.
- **Detalle de la visita** (`/agenda/visita/:id`, `/calendario/visita/:id`, `/mis-visitas/visita/:id`): A hace el armazón y las secciones de agenda (encabezado, estado, equipo, reparto, historial, acciones Reprogramar, Reasignar, Cancelar, Notificar e Iniciar). B agrega las de ejecución (formulario y acta) en su bloque. Se acuerda con B por la integradora.
- **Selectores de los e2e:**
  - Con `ui/selector`, confirmar que el campo editable expone el rol y el nombre que usan los e2e (`textbox` con el nombre de la etiqueta, opciones `option`). Si no, se ajusta el helper `elegir()` en el e2e, nunca el texto.
  - La hora se elige con dos `<select>` nativos («Hora: hora» y «Hora: minutos»).
  - Las confirmaciones de borrar o descartar quedan como el monolito (`window.confirm`) o con el modal de `ui/`; lo que se use queda anotado en el e2e.
- **e2e** en `e2e/pruebas/`:
  - `catalogos-y-agenda.spec.ts` (prueba 4 y prueba 11), `solicitudes.spec.ts` y `propuesta.spec.ts`.
  - Los tramos de agenda de `roles.spec.ts` (403 en `/api/agenda/opciones`) y de `prueba-en-sitio.spec.ts` (menú por rol).
  - `responsivo-360.spec.ts` ya recorre solo las pantallas nuevas.
  - El tramo de la prueba 11 que navega a `/seguimiento` y el estado «En ejecución» son míos. Lo que dependa de pantallas de B queda en `test.fixme` con el motivo.
  - Corren con `DIGSA_E2E_GATEWAY=real` (gateway, seguridad y visitas de la sesión) o con el mock si la integradora prefiere ampliarlo (D7).
- **Entrega:** `npm audit` completo, lint de plantillas (accessibility) y `DESVIACIONES_A11Y.md` si algo choca con la paridad.

## Decisiones que necesito antes de VA2 (técnicas → integradora)

- **D1 · Notificación previa con `notificaciones`.** Hoy el envío es síncrono y decide `notificada`. Opciones:
  - (a) Igual que el D2 de seguridad: un adaptador provisional SMTP a Mailpit detrás de un puerto, síncrono, con paridad exacta. Cuando exista `notificaciones` se cambia por (b).
  - (b) Evento `visita.notificacion_solicitada` con los destinatarios ya resueltos (visitas es dueña de esos datos). `notificaciones` responde `correo.enviado` o `correo.fallido` con la referencia `visita:ID`, y visitas aplica la foto y pasa a `notificada` al recibirlo. La pantalla mostraría «en envío» por unos segundos.
  - **Recomiendo (a) ahora y (b) en el cierre**, porque `notificaciones` aún no existe. Pero los acuerdos dicen que nunca se envía correo desde visitas: necesito la excepción explícita o el contrato del evento de vuelta.
- **D2 · Outbox y copia de usuarios.** ¿S2 (eventos `usuario.*`) y el outbox de `comun` llegan a `develop` antes del cierre de VA1? Si no, VA1 cierra con la copia sembrada y el consumidor pendiente. Para los e2e necesito una forma de que un usuario creado por `POST /api/usuarios` aparezca en `usuarios_copia`: el evento real, o un endpoint `/interno/` solo del perfil local.
- **D3 · `Almacen`.** No está todavía en `librerias/comun` (ADR-005). ¿La agrega la integradora, o la escribo yo en `comun` en mi rama?
- **D4 · Gateway.** Necesito las rutas `/api/catalogos/**`, `/api/maestros/**`, `/api/agenda/**`, `/api/calendario/**`, `/api/mis-visitas/**`, `/api/solicitudes/**` y `/api/propuesta/**` hacia `${LUME_VISITAS_URL}` con TokenRelay, y la variable `LUME_VISITAS_URL` en `generar-env.sh`. El gateway es de la integradora.
- **D5 · Carga masiva de maestros.** ¿Queda fuera de la oleada, o la parto (puntos y auditores en visitas; usuarios en seguridad)? Si el Anexo la exige, puede ser negocio y la pregunto al usuario.
- **D6 · Parámetros.** ¿Aceptas que la pestaña Parámetros solo muestre las claves de visitas, y que las de bandeja y alertas pasen a la pantalla de `notificaciones`?
- **D7 · e2e.** ¿Corren contra el gateway y los servicios reales de mi sesión, o amplío el mock de `frontend/mock`?
- **Frontera con B** (Prórrogas, detalle de la visita, `RepositorioVisitas` como API interna): la cierras tú con las dos.

## Dudas de paridad que dejo como en el monolito (salvo que se diga otra cosa)

- Editar una visita con fecha pasada obliga a poner fecha nueva (`exigirFuturo`).
- `iniciar` no exige `notificada` ni valida la fecha.
- Cancelar está permitido desde `ejecucion`.
- El Administrador no registra solicitudes (duda 9.12).
- Los textos de correos en Catálogos están en mayúscula y los de Maestros en minúscula.
- No se puede editar un punto cuya fuerza o regional está inactiva.
- Los parámetros editables sin rango se dejan igual. Uno de ellos es `riesgo_estado_pesos`.
- `GET /api/maestros/puntos` corta en 2000 filas sin avisar.
- Borrar un valor de catálogo no verifica su uso.
