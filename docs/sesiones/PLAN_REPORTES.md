# Plan de la sesión REPORTES Y NOTIFICACIONES (oleada 2)

Rama `reportes`, worktree `dev-digsa-arqui-reportes`, desfase +500 (reportes 9603, notificaciones 9604, PostgreSQL 5445, RabbitMQ 6172).
Base: `develop` @ `abfbe98`. Monolito leído: `digsa-java` @ `1ff82de`, con tres exploraciones (explorador-codigo): notificaciones, motor de reportes, y pantallas con sus e2e.
Estado: **propuesta, pendiente de aprobación**. No se escribe código del servicio hasta que se apruebe.

## 0. Lo que encontré en el monolito y cambia el prompt

1. **Exportación y la pantalla Exportación no son de esta sesión**: van a VISITAS B (ACUERDOS_OLEADA_2). En cambio sí recibo:
   - **Alertas** (`/api/alertas`, pantalla Alertas, `alertas.spec`);
   - la pestaña **Descargas del día** de Reportes.
2. **El motor tiene 12 proveedores de reporte**. Cuatro son de presupuesto y **se quedan en el monolito** (ver §4). Los otros ocho se reparten así:

   | Reporte | Dueño de los datos | Cómo lo obtiene `reportes` |
   |---|---|---|
   | `detalle`, `lugar`, `proceso`, `distribucion` | visitas (+ maestros, + nombre del auditor) | **Proyección** alimentada por eventos, con carga inicial y conciliación por API (ADR-002, ADR-005) |
   | `dashboard_visitas`, `dashboard_riesgo` | visitas (`tablero`, VISITAS B; leen además OPL, planes e incidencias de riesgo) | **Proveedor remoto** por API interna |
   | `logs_auditoria` | seguridad (`log_transacciones`) | Proveedor remoto (ya previsto en S3 de seguridad) |
   | `correos_envios` | notificaciones (`correos_salida`) | Proveedor remoto (lo expongo yo en R1) |

   Los dos dashboards no van por proyección. Replicar en `reportes` el cálculo de riesgo, con sus pesos y umbrales, duplicaría la lógica de `tablero`; por eso los dejo como proveedor remoto.
3. **La verificación no es pública ni usa firma.**
   - Requiere sesión (cualquier perfil).
   - Guarda un código aleatorio `RPT-aaaammdd-XXXXXXXX`, la huella SHA-256 de los datos (serialización canónica) y la del archivo.
   - Tope: 30 verificaciones por usuario y minuto.
   - El PDF sale cifrado AES-256 sin permiso de modificar, que es la respuesta de la entidad a la observación 36.
   - Se mantiene igual (paridad).
4. **Convivencia (ADR-008 §5, ya aceptado por el usuario).** El monolito conserva su propia bandeja, sus alertas y su bitácora para presupuesto y analítica. Por eso:
   - **no se migran** las plantillas `reporte_presupuesto` ni `presupuesto_notificacion`;
   - **no se migran** las alertas `presupuesto_umbral`, `presupuesto_desviacion` ni `analitica_alerta_alta`;
   - `/api/correos` y `/api/alertas` del sistema nuevo no las muestran.
5. **Los envíos «directos» del monolito pasan a HTTP síncrono hacia `notificaciones`.** Son MFA, enlace de clave, código del OPL, notificación previa, avisos del OPL, seguimiento, reporte a la DIGSA, reporte por correo y logs por correo. El monolito los registraba con `sincrono=true` y sin reintento desde la bitácora; eso se conserva. El acta pasa a evento + bandeja con reintentos (D-N1). Los avisos de solicitudes y el recordatorio de seguimiento eran encolados en el monolito y siguen encolados (evento `aviso.*`).

## 1. Fase R1 · servicio `notificaciones`

Paquetes del monolito que absorbe: `correos`, `alertas`, y de `plataforma` la bandeja, el despachador, el correo y el SMS. No absorbe la bitácora (es de `seguridad`) ni el almacén (es de `comun`).

### R1.0 · Contratos primero (se entregan antes del resto, para que seguridad y visitas cambien su adaptador provisional)

- **`contratos/notificaciones.yaml`** (OpenAPI, `interfaceOnly`, como seguridad):
  - `/api/correos/**` y `/api/alertas/**`, con los mismos cuerpos, códigos y textos del monolito.
  - **`POST /interno/envios`** (alcance `notificaciones:enviar`, aud=notificaciones):
    - Cuerpo: `{origen, referencia, plantilla, variables{}, bloques{}, para[], copia[], critico, modo: directo|bandeja}`. Admite adjuntos en multipart, con un tope de 10 MB en total.
    - En modo `directo` responde 200 `{id, estado: enviado|parcial|fallido, rechazados[], error?}`. Es la paridad de `EnvioRegistrado`: queda registrado con `sincrono=true`, no se reintenta desde la bitácora y los adjuntos se guardan solo como nombre, tipo y tamaño.
    - En modo `bandeja` responde 202 `{id}` y el resultado final sale como evento `envio.resultado`.
  - **`POST /interno/envios/secreto`** (alcance `notificaciones:enviar_secreto`):
    - Igual que el anterior, más `secretos{}` (código o enlace).
    - Solo existe en modo directo.
    - Lo guardado lleva el secreto enmascarado (`••••••` / `[enlace oculto]`), con `enmascarado=true` y `reintentable=false`. El secreto nunca se escribe en logs ni en excepciones.
  - **Por qué `plantilla + variables` y no el HTML ya armado** (como hace hoy `CorreoSeguridad.Envio`): en el monolito las plantillas se editan desde la pantalla Correos > Plantillas, así que deben vivir en `notificaciones`. Si cada servicio mandara su propio HTML, la edición no tendría efecto.
  - **Los `bloques`** (tablas de hallazgos, acciones) son HTML que arma quien llama. Al recibirlos, `notificaciones` los valida con la misma lista blanca de etiquetas del editor (`Plantilla.validar`) y rechaza con 422 lo demás, porque ya no los genera código de confianza del mismo proceso.
- **`contratos/eventos/notificaciones.yaml`**: `envio.resultado {referencia (tal cual llegó, p. ej. acta:{id}:{envio}), estado enviado|agotado, intentos, ultimoError?}` y `auditoria.*`.
- **Propuesta de `aviso.*` y `alerta.*` para `contratos/eventos/visitas.yaml`** (lo escribe visitas; yo propongo la forma por la integradora):
  - `aviso.<tipo>`: `{referencia, plantilla, variables NO secretas, destinatarios[], secretoEn?}`. `secretoEn` es la ruta `/interno/...` donde `notificaciones` pide el secreto o el adjunto (patrón T1 de visitas-b).
  - `alerta.<tipo>`: `{tipo, clave, detalle}`, sin datos personales.

### R1.1 · Esqueleto y esquema (Flyway V1.. en el esquema `notificaciones`, con dos roles y `GRANT` mínimos)

- `servicios/notificaciones`: pom, aplicación, `application.yml` y `application-local.yml` (Mailpit 127.0.0.1:1025), y la línea `<module>` en el pom padre (pedida a la integradora). Cadena de recurso de `comun`.
- Tablas, conservando nombres y columnas del monolito:
  - `correos_salida` (V17+V90): `alerta_id` sin FK a otro esquema; `reintentado_por` guarda el `uid` sin FK.
  - `sms_salida` (V17): `usuario_id` = `uid` sin FK; el teléfono, solo enmascarado.
  - `alertas_criticas` y `alertas_eventos` (V17).
  - `correo_plantillas_versiones` (V91): solo `SELECT, INSERT`.
  - `parametros` (`bandeja_retencion_dias`=365 y `alertas_bloqueo_cuentas`=5; D6 de visitas-a).
  - `usuarios_copia`: copia de `usuario.*` para destinatarios de alertas, con `uid`, nombre, correo, activo y perfiles, sin documento.
  - `outbox` y `mensajes_procesados` de `comun`.
- **Retención (ADR-004/005):**
  - El rol de la app **no tiene DELETE** sobre `correos_salida` ni `sms_salida`.
  - La purga a 365 días la hace una función `SECURITY DEFINER` (patrón V4 de seguridad), que solo borra lo terminado y nunca lo pendiente.
  - Audita `correos_purgados` solo con conteos.
- Semilla del catálogo de alertas: los 8 tipos que migran (`tarea_diaria_fallida`, `correo_agotado`, `integridad_archivo`, `almacen_no_disponible`, `bloqueo_masivo`, `intervencion_inmediata`, `plan_opl_vencido`, `reporte_lento`), con perfiles, criticidad y SMS como en V17 y V51.

### R1.2 · Plantillas

- Motor `Plantilla` portado tal cual:
  - `{{variable}}`, formatos HTML, TEXTO y SIN_MARCO, marco con su pie;
  - escapado `HtmlUtils`, bloques sin reexpandir, asunto sin saltos;
  - validación con lista blanca y los textos exactos.
- **23 plantillas** (las 25 del monolito menos las 2 de presupuesto), copiadas byte a byte, cada una con su `*FabricaTest` portado:
  - Agenda 4, Ejecución 4, Identidad 2 (no editables), OPL 10 (`opl_codigo` no editable);
  - `reporte_motor`, `reporte_logs` y `alerta`.
- `CatalogoPlantillas` (versión vigente o de fábrica; si la lectura falla, sale la de fábrica) y `/api/correos/plantillas/**` (listar, detalle, vista previa, guardar y restaurar con motivo, historial), solo ADMINISTRADOR.
- Pruebas: `PlantillaTest`, `PlantillasApiTest` y los ocho `*FabricaTest`.

### R1.3 · Bandeja de salida, despachador, correo y SMS

- `BandejaSalida.encolar`:
  - corre en la transacción de quien llama;
  - parte en lotes de 100 destinatarios;
  - guarda los adjuntos como ruta del `Almacen` de `comun`;
  - `reintentar` con `ON CONFLICT (reintento_de)`.
- `DespachadorSalida`:
  - envío inmediato tras el commit y respaldo cada 15 s;
  - toma de una fila con `FOR UPDATE SKIP LOCKED` y los críticos primero;
  - reintentos 1 min / 5 min / 30 min / 2 h hasta 5;
  - resultado guardado solo si `intentos` coincide;
  - recuperación de colgados (umbral 2 × los tiempos SMTP, mínimo 2 min) y directos colgados → fallido;
  - `correo_fallido` / `sms_fallido` en la auditoría.
  - Al agotarse un correo crítico se dispara la alerta `correo_agotado`, sin bucle.
  - Al agotarse un envío que vino de un `aviso.*` se publica `envio.resultado agotado` (D-N1).
- Adaptadores:
  - `CorreoSmtp` (parcial, rechazado, sin conexión, con los textos del monolito); en local apunta a Mailpit. Destinatarios de prueba `@reportes.prueba.local`.
  - `SmsArchivo` (solo local) y `SmsNinguno`.
  - `ValidacionArranque`: archivo o Mailpit solo en local; en producción sin proveedor de SMS no arranca (ver duda U-2).
- Ejecutores separados: correo con 2 hilos y cola de 4; SMS con 1 hilo, para que un SMTP lento no frene un SMS crítico.
- Pruebas: `BandejaSalidaTest` (rollback, inmediato, reintento y agotado, dos despachadores sin duplicar, colgados, SMTP lento, prioridad, purga 365) y `EnvioRegistradoTest`.

### R1.4 · Entradas: API interna y eventos

- `/interno/envios` y `/interno/envios/secreto` (R1.0), con Resilience4j del lado de quien llama (lo documento en el README del servicio).
- Consumidor `notificaciones.avisos` sobre `visitas.eventos` (`aviso.#`). Es idempotente (`mensajes_procesados`) y encola en la bandeja.
  - Para `aviso.acta_generada`, el secreto (enlace del OPL) y el PDF se piden en cada intento a `GET /interno/avisos/acta/{id}` de visitas (alcance `visitas:avisos`). No se guardan.
  - El enlace queda **oculto** en el registro.
  - El resultado sale en `envio.resultado` con la referencia tal cual llegó.
- Prueba de que un `origen` falsificado va a la DLQ y de que un aviso repetido no envía dos veces.
- **Regla B5** (integradora, 2026-11-06): el bloque secreto solo va a `para`. `copia` recibe el mismo correo sin él, en un segundo mensaje SMTP del mismo envío, con una sola referencia y un solo `envio.resultado` (lo decide `para`). En el registro quedan los dos cuerpos. Prueba con Mailpit: el de `copia` no contiene el enlace, y el `copia` caído no agota el aviso.

### R1.5 · Alertas

- `ServicioAlertas`:
  - crítica abierta y única por clave, con `veces`;
  - operativa con aviso único;
  - repetición de correo y SMS como máximo 1 por hora;
  - plantilla `alerta`;
  - destinatarios = usuarios activos de los perfiles + usuarios elegidos (tope 200), tomados de la **copia `usuario.*`**.
- Disparadores:
  - consumidor de `alerta.*` de `visitas.eventos` y de `reportes.eventos` (`reporte_lento`);
  - `cuenta.bloqueada` de `seguridad.eventos` → `bloqueo_masivo` cuando hay N o más en la última hora, que se cierra al bajar del umbral;
  - `correo_agotado` interno;
  - `MonitorSalud` de **su** base y **su** almacén, con aviso directo si cae la base (como máximo 1 por hora por réplica).
- **Teléfono para el SMS** (punto abierto de ACUERDOS): no viaja en `usuario.*` (minimización). Propuesta:
  - `POST /interno/usuarios/moviles` en seguridad (alcance `seguridad:moviles`, aud=seguridad), con cuerpo `{uids[]}` → `{uid: movil|null}`;
  - se llama **al momento de enviar** cada SMS (paridad: el monolito relee el teléfono vigente al enviar);
  - para el conteo `sinTelefono` del catálogo se llama con los destinatarios;
  - el número nunca se guarda completo: solo enmascarado `+573*******67`;
  - si seguridad no responde, el SMS se reintenta (no se omite), porque es crítico.
- `/api/alertas/catalogo` (GET/PUT, solo ADMINISTRADOR) y `/api/alertas/eventos` (+ cerrar; ADMINISTRADOR y COORDINADOR), con métricas de la bandeja de 24 h, con los mismos textos.
- Pruebas: `AlertasApiTest` y `reglas/AlertasTest` portados. El caso «teléfono E.164 al guardar el usuario» es de seguridad y no lo porto.

### R1.6 · Bitácora de correos, destinatarios y proveedor remoto

- `/api/correos/opciones`, `/envios` (keyset de 100, filtros, destinatarios enmascarados), `/envios/{id}` (vista previa, que audita `correo_destinatarios_vistos`) y `/envios/{id}/reintento` con motivo. Mismos textos (`CorreosApiTest`).
- **Destinatarios**:
  - Las 13 reglas fijas viven en `notificaciones`.
  - El buzón de la DIGSA (`correo_digsa`), los buzones de cada fuerza y el correo del OPL son de **Catálogos (visitas)**.
  - Propuesta: `notificaciones` los lee y los edita por `/interno/catalogos/buzones` de visitas (alcance `visitas:buzones`). El motivo y la auditoría se conservan, y la pantalla queda igual.
  - Alternativa: la pantalla los edita directo en `/api/catalogos` de visitas. Es menos trabajo para visitas, pero cambia la pantalla (ver T-4).
- Proveedor remoto `correos_envios` (contrato de §2, R2.6), con tope de 200 000 filas.
- Auditoría por el outbox (`auditoria.*`), con los mismos tipos del monolito.

### R1.7 · Gateway, entorno y humo

- Rutas `/api/correos/**` y `/api/alertas/**` → `LUME_NOTIFICACIONES_URL` (`TokenRelay`, sin `Cookie` ni `Set-Cookie`), con una prueba en `GatewayTest`. `LUME_NOTIFICACIONES_URL` y `LUME_REPORTES_URL` van en `generar-env.sh`.
- `infra/humo.sh`: un envío directo y uno por bandeja leídos en Mailpit, filtrados por `@reportes.prueba.local`.
- Cuando R1 esté en `develop`, seguridad (D2) y visitas-a (D1) cambian a su adaptador definitivo y retiran el SMTP provisional (lo hace cada dueño). Les dejo un cliente de ejemplo en el README.

## 2. Fase R2 · servicio `reportes`

### R2.0 · Contratos

- `contratos/reportes.yaml`: `/api/reportes/**` (catálogo, opciones, ver, exportar, descargas, tiempos, enviar, verificar) y las rutas de compatibilidad `/api/reportes/{tipo}` (`ReportesApiTest`), con los mismos textos y códigos.
- **`contratos/proveedor-reportes.yaml`** (proveedor remoto):
  - `GET /interno/reportes` devuelve las definiciones (`DefinicionReporte` serializada).
  - `GET /interno/reportes/{id}/opciones?filtro=` devuelve las opciones recortadas al alcance.
  - `POST /interno/reportes/{id}/filas` devuelve un flujo NDJSON de filas tipadas (`TiposCelda`). El dueño lo genera en una transacción `REPEATABLE READ` de solo lectura, con su `statement_timeout`.
  - El alcance y los perfiles del usuario viajan en el cuerpo, porque quien llama es un token de máquina (`<svc>:reportes`). El dueño valida y aplica el alcance; `reportes` no confía en sí mismo para eso.
  - Los implementan seguridad (`logs_auditoria`), notificaciones (`correos_envios`) y visitas-b (`dashboard_*`).

### R2.1 · Marco y exportador en `librerias/comun` (ADR-002: «el marco de reportes y el exportador van a `comun`»)

- `comun.reportes`: `DefinicionReporte`, `ProveedorReporte`, `ConsultaReporte`, `Opcion`, `GrupoReporte`, `TiposCelda`, `HuellaDatos` y `CodigoVerificacion`, puros y sin base de datos.
- `comun.exportacion`: `ExportadorFlujo` (xlsx con POI SXSSF, csv `;` con BOM y neutralización de fórmulas, txt, pdf OpenPDF cifrado) y `CintaFilas`, con dependencias opcionales.
- Seguridad lo necesita para `/api/logs/exportar`, lo que evita dos copias.
- Commits «comun:» compatibles (como D3 de visitas-a). Las versiones de POI, OpenPDF y BouncyCastle son las del monolito, por el pom padre (las pide la integradora) y verificadas sin CVE abierta.

### R2.2 · Esquema (Flyway V1.. en el esquema `reportes`)

- `reportes_generados` (V50+V52):
  - las mismas columnas y CHECK;
  - `usuario_id` = `uid` **sin FK**;
  - el rol de la app solo tiene `SELECT, INSERT` + `UPDATE` de las columnas de estado;
  - **sin DELETE**: el registro nunca se purga (decisión 7.5).
- Proyección, con nombres del monolito para portar el SQL sin reescribirlo:
  - `visitas`, `visitas_ejecucion`, `visitas_resumen`, `visitas_componentes`, `visitas_actas` (solo la vigente), `visitas_hallazgos`, `visitas_respuestas`, `visitas_auditores`;
  - maestros `puntos`, `par_municipios`, `par_fuerzas`, `par_unidades`, `par_regionales`, `par_tipos_visita`, `chk_componentes`, `chk_items`, `chk_versiones`, `auditores`;
  - `usuarios_copia` (uid, nombre, activo).
  - Cada agregado lleva su `version`, y `proyeccion_estado` guarda la hora del último evento aplicado.
- `verificaciones` (contador por usuario y minuto, **compartido entre réplicas**; el monolito lo tenía en memoria).

### R2.3 · Proyección (pieza nueva, la de más riesgo)

- Consumidores:
  - `reportes.visitas` (`visita.#`, `hallazgo.#` y, **si la integradora amplía la matriz**, `punto.#`, `regional.#`, `fuerza.#`, `checklist.#`, `catalogo.#`);
  - `reportes.usuarios` (`usuario.#`).
- Aplicación con UPSERT condicional por `version`, lista blanca de tipos e idempotencia.
- **Evento por agregado, con foto completa** (lo que necesito que visitas publique; lo propongo por la integradora en `contratos/eventos/visitas.yaml`):
  - `visita.*`: la foto de la visita con estado, fecha, tipo, punto, líder, versión del checklist, equipo (`auditores`), componentes y ejecución (inicio y fin reales, objeto).
  - Al cerrar o reabrir, además: resumen (`c, nc, na, hallazgos, por_componente`), acta vigente (`id, estado, ronda`), hallazgos (`codigo, hallazgo, texto, inmediata`) y **respuestas por ítem** (`item_id, calificacion`), que necesita `proceso`.
  - Cabe en 256 KiB (decenas de ítems).
  - Reabrir deja vacíos los resultados (R.18).
- Carga inicial y conciliación nocturna por API (ADR-005): `/interno/reportes/proyeccion` de visitas (paginada por id, con la misma foto y versión) y `/interno/usuarios/exportacion` de seguridad (ya existe en su plan).
- Cada reporte muestra **«Datos al: {hora del último evento aplicado}»** (consecuencia aceptada en ADR-002) en el encabezado en pantalla y en el archivo. Va **fuera** de la huella, para que la verificación siga siendo determinista.
- Riesgo de paridad: con REPEATABLE READ sobre la proyección, dos usuarios en el mismo instante ven lo mismo (2.10). Lo que cambia es que la proyección puede ir unos segundos detrás de visitas. Se mide y se informa el retraso (métrica de Prometheus).

### R2.4 · Motor

- `RegistroReportes`, que reúne los proveedores locales y los remotos descubiertos al arrancar; si un remoto no responde, su reporte no aparece y se reintenta. Además:
  - `MotorReportes`: validación y textos exactos; transacción propia de solo lectura con `REPEATABLE READ`; `statement_timeout` y corte con `ReporteCortado`.
  - `ServicioGeneracion`: pantalla con 5000 filas + truncado; archivo por cola de 4 hilos y 20 puestos; cupo de 2 por usuario con candado consultivo; espera síncrona de 15 s y luego 202 diferido.
  - `RegistroGenerados`, `LimpiezaReportes` (fin del día en Bogotá, una sola réplica, reposición de huérfanas) y `MedicionReportes` (publica `alerta.reporte_lento` por el outbox).
- Archivos en el `Almacen` de `comun` (volumen por servicio). **Con más de una réplica hace falta almacenamiento compartido** (I9, ADR-005).
- Pruebas portadas (unitarias sin cambios): `ContratoReportesTest`, `HuellaYCintaTest`, `MotorReportesTest`, `ExportadorFlujoTest` y `PlantillasReportesFabricaTest` (pasa a notificaciones).

### R2.5 · Los 4 reportes de visitas sobre la proyección

- `ReporteDetalleVisitas`, `ReporteVisitasLugar`, `ReporteVisitasProceso`, `ReporteDistribucion` y `FiltrosVisitas`, con el SQL del monolito sobre las tablas proyectadas.
- Alcance desde el JWT (`alcance` estructurado de S1.4: Todo, Opl, Fuerza, Regional, Esm, Equipo o Ninguno, que falla cerrado).
- Pruebas: `MotorReportesApiTest`, `IntegridadApiTest`, `DiferidoApiTest`, `TopeTecnicoApiTest` y `ReportesApiTest`, sembrando la proyección por eventos (no por SQL).
- `VolumenReportesApiTest` (1,5 M de visitas): ver ≤ 3 min y exportar ≤ 10 min. Corre solo con `-Dlume.volumen=true`; con el agente rendimiento.

### R2.6 · Proveedores remotos

- Cliente HTTP con token de máquina, Resilience4j (tiempo de espera igual al tope técnico, sin reintento a mitad del flujo) y lectura NDJSON en streaming a la cinta.
- `correos_envios` lo pruebo contra mi propio `notificaciones`. `logs_auditoria` y los `dashboard_*` dependen de seguridad S3 y de visitas-b: los integro cuando estén en `develop`, con una prueba de contrato contra un proveedor simulado mientras tanto.
- `ProveedoresR4ApiTest` portado (tableros, logs, tiempos, envío a varios correos).

### R2.7 · Verificación y envío por correo

- `ControladorVerificacion`, con los mismos textos, iniciales a quien no generó el reporte y filtros ocultos.
- `regenerar`: vuelve a ejecutar el proveedor (local o remoto) con el alcance de quien verifica.
- `POST /api/reportes/{id}/enviar`:
  - genera por la misma cola;
  - envía por `/interno/envios` modo directo con adjunto (≤ 10 MB);
  - respeta el máximo de 15 destinatarios y los dominios permitidos;
  - devuelve la respuesta parcial con los rechazados.

### R2.8 · Gateway

`/api/reportes/**` → `LUME_REPORTES_URL`, con una prueba en `GatewayTest`. `/api/presupuesto/reportes/**` ya va al monolito.

## 3. Fase R3 · Pantallas Angular y e2e

- `frontend/src/app/paginas/reportes/`: Reportes, con las pestañas Generar, Descargas del día, Verificar reporte y Tiempos. El aviso de descargas terminadas va en un **servicio** que vive aunque la pestaña no se vea, como en el monolito, con sondeo cada 5 s solo mientras hay algo en curso.
- `frontend/src/app/paginas/correos/`: Correos, con las pestañas Envíos, Plantillas y Destinatarios. Lleva iframe `sandbox` con título y el botón con motivo enfocable (lo verifica la e2e).
- `frontend/src/app/paginas/alertas/`: Alertas (Catálogo y Eventos, tarjetas a 360 px, foco en el error al cerrar, sondeo de 30 s).
- Con el design system de `ui/` y su bloque en `rutas-modulos.ts`. Se conservan todos los `data-testid`, los textos y los nombres accesibles que usan las e2e.
- e2e adaptadas contra el gateway y los servicios reales de la sesión (como D7 de visitas): `reportes-motor` (R1–R5), `correos` (3 casos), `mailpit`, `alertas` (los 2 casos de Alertas; el del teléfono es de Usuarios) y la parte de reportes de `formulario-acta-reportes`, que depende de visitas-b.
- Diferencias de selectores por cambio de librería (MultiSelect, Switch, SegmentedControl): se ajusta la prueba **sin cambiar lo que verifica**, y queda anotado. Revisor-angular y qa-e2e en cada unidad. `npm audit` completo.

## 4. Lo que se queda en el monolito (marcado aquí y en `docs/` del servicio)

| Qué | Por qué |
|---|---|
| Reportes `presupuesto_ejecucion`, `presupuesto_mensual`, `presupuesto_movimientos`, `presupuesto_alertas` | Presupuesto no se migra (ADR-008). Siguen en Presupuesto > Reportes del monolito (`/api/presupuesto/reportes`). **Desaparecen del catálogo de `/reportes`** (U-1) |
| Exportaciones de analítica (`ExportadorAnalitica`, homologación) | No pasan por el motor; analítica no se migra |
| Plantillas `reporte_presupuesto` y `presupuesto_notificacion`; alertas `presupuesto_umbral`, `presupuesto_desviacion` y `analitica_alerta_alta` | ADR-008 §5: bandeja y alertas del monolito para lo suyo |

## 5. Orden, dependencias y ritmo

1. R1.0 contratos → R1.1 → R1.2 → R1.3 → R1.4 → R1.5 → R1.6 → R1.7. **Entrego R1 completa.** Antes entrego R1.0 suelta, para que seguridad y visitas preparen sus adaptadores.
2. R2.0 y R2.1 (`comun`) pueden ir en paralelo a R1.5–R1.6. R2.3 depende de que visitas publique `visita.*` con la foto. Mientras tanto la pruebo con eventos sembrados que cumplen el contrato.
3. R3 después de R2 (Reportes) y de R1 (Correos y Alertas). La pantalla Correos puede adelantarse al terminar R1.

Cada unidad sigue el mismo ciclo: qa-tdd escribe las pruebas → código → revisor-java (+ revisor-bd-postgresql en las migraciones; rendimiento en R1.3, R2.4 y R2.5) → qa cierra. Por fase entrego: hash, pruebas por módulo contadas desde `mvnw clean verify` en árbol limpio, y dudas.

## 6. Dudas

### Para el usuario (negocio) — **decididas el 2026-11-06**

- **U-1 → quitarlos.** Los 4 reportes de presupuesto desaparecen del catálogo de «Reportes». Siguen en Presupuesto > Reportes del monolito, sin código de verificación, mientras presupuesto no se migre.
- **U-2 → se decide en el cierre (I9).** Quedan el puerto de SMS y los adaptadores «archivo» (local) y «ninguno», como en el monolito. El proveedor real se conecta en I9.

Texto original de las dudas:

- **U-1 · Reportes de presupuesto fuera del catálogo de «Reportes».** Hoy el Coordinador los ve en la pantalla Reportes (con código de verificación) y también en Presupuesto > Reportes (sin código). El servicio nuevo no puede pedirle filas al monolito: no se modifica y no tiene un proveedor remoto. Propongo que desaparezcan de «Reportes» y sigan en Presupuesto > Reportes, **sin código de verificación** mientras presupuesto siga en el monolito.
- **U-2 · Proveedor real de SMS.** El monolito no tiene ninguno contratado: en producción no arranca sin él, y en local escribe archivos. Dejo el puerto y los adaptadores de archivo y «ninguno». ¿Qué proveedor se usará en producción (p. ej. un agregador con API HTTP) y cuándo? Se puede decidir en el cierre (I9); no bloquea R1.

### Para la integradora (técnicas)

- **T-1** Contrato de envío (R1.0): `plantilla + variables + bloques + secretos`, modos `directo` y `bandeja`, alcances `notificaciones:enviar` y `notificaciones:enviar_secreto`. ¿De acuerdo? Afecta a los adaptadores definitivos de seguridad y visitas.
- **T-2** Ampliar la matriz de RabbitMQ:
  - `notificaciones` lee `usuario.*` y `cuenta.*` de `seguridad.eventos` (destinatarios de alertas y `bloqueo_masivo`) y `alerta.*` de `reportes.eventos` (`reporte_lento`);
  - `reportes` lee `punto.*`, `regional.*`, `fuerza.*`, `checklist.*` y `catalogo.*` de `visitas.eventos` (maestros de la proyección e `inicio_contrato` para los presets).
- **T-3** (aprobada; la carga inicial de las copias usa `/interno/usuarios/exportacion` con el alcance `seguridad:exportar_usuarios`, y «reportes» además lleva `notificaciones:enviar`) Pedir a seguridad `POST /interno/usuarios/moviles` (alcance `seguridad:moviles`) y registrar los clientes `reportes` y `notificaciones` con sus alcances: `seguridad:reportes`, `seguridad:moviles`, `visitas:avisos`, `visitas:buzones`, `visitas:reportes`, `notificaciones:reportes`.
- **T-4** Destinatarios: ¿`/interno/catalogos/buzones` en visitas (la pantalla no cambia) o la pantalla edita directo en `/api/catalogos`? Recomiendo lo primero.
- **T-5** Pedir a visitas (A y B): la foto de `visita.*` de R2.3 con resumen, acta vigente, hallazgos y respuestas por ítem; los eventos de maestros; `/interno/reportes/proyeccion`; el proveedor remoto de `dashboard_*` (B) y `/interno/catalogos/buzones` (A).
- **T-6** Marco de reportes y `ExportadorFlujo` a `librerias/comun` desde mi rama (commits «comun:»), compartidos con seguridad S3. Versiones de POI, OpenPDF y BouncyCastle en el pom padre.
- **T-7** Dos líneas `<module>` en el pom padre (`servicios/reportes`, `servicios/notificaciones`) y las rutas del gateway de R1.7 y R2.8 en mi rama, con su prueba en `GatewayTest`.
- **T-8** Dependencias nuevas, justificadas en cada entrega: `spring-boot-starter-mail` (notificaciones), POI, OpenPDF, `openpdf-fonts-extra`, `bcprov-jdk18on` y Resilience4j (si no está ya en el pom padre).
