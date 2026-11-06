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

## Frontera (aprobada)

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

## Estado

- **Plan aprobado** por la integradora el 2026-11-06. Las decisiones D1–D7 están en `ACUERDOS_OLEADA_2.md` (`develop` b009640) y abajo, en «Decisiones tomadas».
- Ajuste después de leer el plan de VISITAS B (§3): VA1 adelanta todo lo que B necesita para empezar. Eso es el esquema completo de visitas, `archivos`, la interfaz Java interna, `Almacen` y el validador de archivos.

## VA1 · Servicio, esquema compartido, catálogos y maestros (desbloquea a B)

- **VA1.0 · Esqueleto, contrato y gateway:**
  - `servicios/visitas`: pom igual al de seguridad sin el servidor de autorización, `VisitasAplicacion`, `application.yml` y `application-local.yml` con los dos roles, `out-of-order=true`, `placeholders.rol_app`, `audiences: visitas` y el puerto `VISITAS_PUERTO`.
  - En el pom padre, solo la línea `<module>servicios/visitas</module>`.
  - `contratos/visitas.yaml` 0.1.0 con `/api/catalogos/**` y `/api/maestros/**`: mismas rutas, cuerpos, códigos y textos del monolito, y `Problema` de `comun.yaml`. Allí se anota la diferencia D6.
  - Gateway (D4): rutas `/api/{catalogos,maestros,agenda,calendario,mis-visitas,solicitudes,propuesta}/**` → `LUME_VISITAS_URL` con `TokenRelay`, `RemoveRequestHeader=Cookie` y `RemoveResponseHeader=Set-Cookie`, más `LUME_VISITAS_URL` en `generar-env.sh`. Lleva su prueba en `GatewayTest`.
  - Soporte de pruebas: `ContenedoresPrueba` con `infra/postgres/init/01-esquemas.sh`, `PruebaIntegracion` y `RelojMovible`, y la prueba de esquema (GRANT mínimos y roles).
- **VA1.1 · `comun:` archivos (D3)**, en commits «comun:» compatibles:
  - `Almacen` (interfaz) con un adaptador de disco por volumen. Ruta relativa segura, como `AlmacenLocal`.
  - `ArchivoIntegridadFallida`: integridad por SHA-256.
  - `ValidadorArchivos`: tipo por contenido con Tika, tamaño (25 MB) y fotos enderezadas por EXIF con metadata-extractor, como el monolito.
  - Las versiones de Tika y metadata-extractor las propongo con fuente oficial, versión fija y firma verificada, y la integradora las fija en el pom padre (ADR-012). Comparto la propuesta con el D-T3 de B.
- **VA1.2 · Esquema** (cada archivo cita su Vnn del monolito):
  - `V1__catalogos.sql`: departamentos, municipios, unidades, fuerzas, regionales, modalidades, tipos de visita, parámetros, festivos, `catalogos` y `catalogo_valores` (de V3).
  - `V2__usuarios_copia.sql`: solo los campos que se usan (uid, usuario, nombre, documento, correo, activo, perfiles directos, alcance de fuerza, punto y regional, y `version`), más `mensajes_procesados`. El documento nunca sale en logs ni en la bitácora: allí solo aparece «modificado».
  - `V3__maestros.sql`: `opl`, `puntos` (con `regional_opl` y `fuente`), `auditores` y `obligaciones_contrato` (de V3, V6, V8:20 y V12:9-17). Sin `centralizador_id`, que es de presupuesto.
  - `V4__checklist.sql` (de V3:164-220) y `V5__checklist_v6.sql` (de V4, v6 vigente).
  - `V6__datos_iniciales.sql` (de V7): fuerzas, catálogos, regionales y los 185 puntos del anexo del OPL. Son datos públicos del anexo, no personales.
  - `V7__visitas.sql` (de V6, V8 y V13): `visitas` con todas sus columnas (`origen_id`, `seguimiento_n`, `reaperturas`, `checklist_version`, `iniciada`), `visitas_componentes`, `visitas_auditores`, `visitas_reparto`, `visitas_historial` (solo SELECT e INSERT) y `visitas_notificaciones`, con sus CHECK, índices y el UNIQUE de origen vigente.
  - `V8__archivos.sql` (de V9:51-66, con el CHECK de clave ampliado en V12:58): la tabla la usan las evidencias y el acta de B.
  - `R__catalogos.sql`: geografía DANE, unidades, modalidades, 9 tipos de visita y las claves de parámetros de visitas, con la misma regla de conservar el valor editado.
  - GRANT con `${rol_app}`: V3:223-235, V6:108, V9 y V12:218.
- **VA1.3 · API de catálogos**: los 11 endpoints, con validaciones y textos literales. `GET /municipios` exige solo autenticación; el resto, la pantalla CATALOGOS. Auditoría con `PublicadorAuditoria` y los tipos de `reglas/Bitacora`. Se veta que un catálogo se llame `fuerzas` o `regionales`.
- **VA1.4 · API de maestros**: puntos (validación acumulada «Revise el punto: …»), estado, auditores (rol técnico y municipio base, contra `usuarios_copia`), OPL (un solo activo, ahora con índice único parcial) y obligaciones, con el puerto `UsoObligaciones`. Contacto y correos van a la bitácora como «modificado», sin el valor.
- **VA1.5 · Copia de usuarios (D2):**
  - Puerto `DirectorioUsuarios` sobre `usuarios_copia`: auditores activos, correos de los coordinadores y conteos por alcance y perfil.
  - `/interno/pruebas/usuarios`, solo con el perfil `local`/prueba, con una prueba de que no existe en otro perfil.
  - El consumidor `usuario.*` (idempotente y ordenado por versión) y la carga inicial y conciliación con `/interno/usuarios/exportacion` entran en cuanto el outbox y los eventos de seguridad estén en `develop`. Si no llegan antes del cierre de VA1, quedan como pendiente con fecha.
- **VA1.6 · Interfaz Java interna para B** (`co.com.agsamericas.digsa.visitas.agenda`), documentada en `servicios/visitas/README.md`:
  - **Implementado en VA1:** `bloquear(visitaId)` (`FOR UPDATE`, regla «primero la visita»), `porId`, `equipo`, `reparto`, `componentesDe`, `historial` y `registrarHistorial`, `cambiarEstado` y `reabrir` (UPDATE condicionado con `reglas/EstadosVisita`, `reaperturas+1`), `seguimientoDe`, la condición de alcance de visitas a partir de `UsuarioActual` y `plazos()` (parámetros y festivos, con caché corta).
  - **Firmas en VA1 e implementación en VA2:** `programarSeguimiento`, `notificarSeguimiento`, `cancelarSeguimientoPendiente`, `iniciar` y `destinatariosNotificacion`. B compila contra ellas desde VA1.
- **Pruebas:**
  - Port de `CatalogosApiTest` (datos iniciales: 1122 municipios y 185 puntos, fuerza en vivo, validaciones, valores, regionales y parámetros, solo coordinador) y `MaestrosApiTest`.
  - Lo que el monolito no cubre: obligaciones, `PUT /puntos/{id}/estado` con usuarios ESM activos y `correo_digsa`.
  - Esquema y GRANT, interfaz interna (máquina de estados y bloqueo), `comun` (`Almacen` y validador) y la prueba de rutas del gateway.

### Cierre de VA1 (2026-11-06): pendientes registrados

- **Consumidor `usuario.*` y `cuenta.*`, con carga inicial y conciliación (`/interno/usuarios/exportacion`).** Entra en la primera unidad
  de VA2 que se haga después de que S2-OUTBOX (outbox, `Topologia` y `ConsumoEventos` de comun) esté en `develop`. Responsable: VISITAS A.
  Mientras tanto la copia se siembra con `/interno/pruebas/usuarios` (solo local y prueba).
- **Eventos `punto.*`, `regional.*` y `fuerza.*` para la copia de seguridad** (ADR-002), con `contratos/eventos/visitas.yaml`. Van en VA2,
  con el outbox. Las claves no están en la matriz de RabbitMQ: se piden a la integradora antes de publicarlas.
- **`AgendaParaEjecucion`**: solo firmas en VA1 (paquete real `co.com.agsamericas.digsa.visitas.agenda`, no `agenda.interno` como decía
  el plan). El bean llega en VA2.7; hasta entonces ningún bean de B debe exigirla para arrancar.
## VA2 · Agenda: visitas, solicitudes, propuesta, calendario y mis visitas

- **VA2.S · Revisión de seguridad de la integradora sobre VA1** (primera unidad de VA2, cada punto con prueba):
  - comun/ValidadorArchivos: el tope se aplica antes de analizar; descompresión hasta 100 MB; sin el genérico msoffice; OOXML sin
    macros (`vbaProject.bin`), `activeX/` ni `embeddings/` (el monolito los aceptaba: riesgo anotado para que decida el usuario); el
    enderezado lee solo el EXIF (sin XMP, sin XXE); el nombre en los mensajes va saneado y cortado.
  - comun/AlmacenDisco: ruta real (enlaces simbólicos), temporal más publicación atómica sin sobrescribir, permisos 0600, tope de 200
    caracteres. ConfiguracionArchivos: el almacén solo existe si el servicio declara `lume.archivos.carpeta` (sin valor por omisión).
  - comun/ConfiguracionRecurso: `/interno/**` solo con token de máquina (sin `uid`).
  - visitas: la pantalla se revisa por ruta antes de leer el cuerpo (403 y no 400); `V10__ajuste_permisos.sql` quita los GRANT sin uso.
  - Descargas (VA2 y B): Content-Type de una lista cerrada, `nosniff` y `Content-Disposition: attachment` con nombre generado.- **VA2.0 · Contrato** 0.2.0: `/api/agenda/**`, `/api/calendario/**`, `/api/mis-visitas/**`, `/api/solicitudes/**` y `/api/propuesta/**`, con los textos de §2 del inventario.
- **VA2.1 · Esquema** (V10–V19): `V11__solicitudes.sql` (de V11) y `V12__propuesta.sql` (de V15); V10 la usa VA2.S, con el trigger de solo borrador.
- **VA2.2 · Visitas:**
  - Operaciones: programar, editar, reprogramar, cancelar, reasignar, mover reparto y detalle.
  - Listados: agenda (≤2000 filas, con alertas de plazo) y `/seguimiento`.
  - Reglas de `reglas/` (`EstadosVisita`, `Plazos`, `Reparto`, `Fechas` y `Festivos`) y de `par_festivos`.
  - Concurrencia como en el monolito. Además, el advisory lock por punto también en `programar` manual.
- **VA2.3 · Notificación previa (D1):**
  - Puerto `EnvioNotificacionPrevia` con un adaptador SMTP provisional a Mailpit (`@visitas-a.prueba.local`), síncrono y con la paridad exacta de la foto y de `notificada`.
  - `visitas_notificaciones` guarda destinatarios, asunto y cuerpo, con retención de 1 año.
  - El definitivo será HTTP síncrono a `notificaciones` (`client_credentials`, `notificaciones:enviar`, Resilience4j). El adaptador provisional se retira cuando ese servicio exista.
- **VA2.4 · Mis visitas y calendario:**
  - Mis visitas: listado, detalle e `iniciar` (idempotente; fija la versión del checklist y rehace el reparto).
  - Calendario: con el alcance del token, la regla «sin necesidad pendiente» y sin equipo para los perfiles de consulta.
- **VA2.5 · Solicitudes:**
  - Registrar con el escrito PDF, usando `Almacen` y `ValidadorArchivos` de `comun`.
  - Anular y descargar el escrito.
  - Los avisos `solicitud_nueva`, `solicitud_agendada` y `solicitud_cancelada` van por el mismo puerto de correo provisional mientras no exista `notificaciones`. El monolito los mandaba por la bandeja de salida.
- **VA2.6 · Propuesta**: generar, ajustar, liberar, recalcular, descartar y confirmar, más la cobertura.
- **VA2.7 · Implementación de las firmas de VA1.6** para B: seguimiento al plan, `iniciar` y destinatarios.
- **Pruebas:**
  - Port de `AgendaApiTest`, `PropuestaApiTest`, `SolicitudesApiTest`, `SolicitudesEstresApiTest` y `PlantillasAgendaFabricaTest`.
  - Las de concurrencia pendientes en el monolito (18.15).

## VA2b · Carga masiva de maestros (D5)

- `maestros/carga`: CLI con simular y aplicar, todo o nada, candado consultivo, informe CSV neutralizado y los códigos de salida 0 a 4, con las reglas de `reglas/ImportadorMaestros`.
- Los usuarios del archivo van por `/interno/usuarios/importar` de seguridad (`seguridad:importar`).
- Orden aprobado (integradora): validar las hojas → en mi transacción, puntos y auditores → último paso antes del commit,
  `/interno/usuarios/importar` (con timeout, Resilience4j) → si falla, rollback. `simular=true` sin efectos. Riesgo residual aceptado
  (seguridad confirmó y mi commit falla): la carga es idempotente; alerta al administrador por `alerta.*` y nota en el README.
- Decisiones del usuario (las aplica seguridad; el informe por fila debe mostrarlas): por carga solo se asignan perfiles locales (ESM,
  OPL, Sanidad, Auditor…); Coordinador, DIGSA, Analista y Administrador se rechazan en la fila. La carga no reactiva a un usuario
  inactivo: la fila sale con aviso y su estado no cambia.
- Pruebas: port de `CargaMaestrosTest`.

## VA3 · Pantallas Angular y e2e

- **Pantallas** en `frontend/src/app/paginas/visitas/`, con las reglas de «Agregar pantallas»:
  - Catálogos (pestañas Fuerzas, Tipos de documento y demás catálogos, Regionales, Parámetros).
  - Maestros (Puntos, Auditores, OPL, Obligaciones del contrato).
  - Agenda, con el formulario de visita, el detalle de la visita en su parte de agenda y Seguimiento.
  - Calendario, Solicitudes (con el visor del escrito), Propuesta (tabla y tarjetas a 360 px) y Mis visitas.
  - Textos y `data-testid` idénticos a los del monolito.
- **Detalle de la visita** (`/agenda/visita/:id`, `/calendario/visita/:id`, `/mis-visitas/visita/:id`): A hace la página completa (encabezado, estado, equipo, reparto, historial, y las acciones Reprogramar, Reasignar, Cancelar, Notificar e Iniciar). B aporta dos componentes, `reabrir-visita` y `respuesta-del-opl`, que A inserta (plan de B §1). El botón «Abrir formulario» lleva a la ruta de B.
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

## Decisiones tomadas (integradora, 2026-11-06)

- **D1:** correo provisional SMTP a Mailpit detrás de un puerto. El definitivo será HTTP síncrono a `notificaciones`.
- **D2:** `/interno/pruebas/usuarios` solo en local/prueba. El definitivo son los eventos `usuario.*`, con `documento` incluido en la foto (aprobado).
- **D3:** `Almacen` y `ValidadorArchivos` los escribe A en `comun`.
- **D4:** las rutas del gateway las escribe A.
- **D5:** la carga masiva entra como VA2b.
- **D6:** aceptada.
- **D7:** los e2e corren contra los servicios reales.
- **Frontera:** aceptada tal como se propuso.

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
