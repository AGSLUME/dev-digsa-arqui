# ADR-006 · Mensajería: RabbitMQ con patrón outbox

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
Hay flujos asíncronos entre servicios: auditoría (todos → `seguridad`), correos y SMS (todos → `notificaciones`), cambios de usuarios y maestros que otros copian. El volumen es bajo (300 concurrentes). El monolito ya tiene una bandeja de salida (`BandejaSalida`, `DespachadorSalida`) para correos.

## Decisión
- **RabbitMQ** (imagen oficial `rabbitmq:<versión>-management` con digest fijo) en lugar de Kafka: menos piezas, colas de mensajes muertos nativas y suficiente para este volumen.
- **Outbox transaccional** en cada servicio que publica: el evento se inserta en la tabla `outbox` del propio esquema **en la misma transacción** que el cambio de negocio. Un despachador (dentro del servicio, con bloqueo `FOR UPDATE SKIP LOCKED`) lo publica y lo marca. Va en `librerias/comun`, para que todos usen el mismo.
- Topología: un exchange `topic` por servicio publicador (`seguridad.eventos`, `visitas.eventos`, ...), con claves `<agregado>.<hecho>` (`usuario.creado`, `visita.cerrada`). Cada consumidor tiene su **cola quorum** (RabbitMQ 4 ya no tiene colas espejo) y su DLQ. El reintento diferido se hace con TTL + *dead-letter exchange*, sin plugins que no vengan en la imagen oficial.
- **Orden por agregado**: cada evento lleva `version` del agregado y el consumidor descarta las versiones menores o iguales a la que ya aplicó (un `usuario.actualizado` reintentado no pisa uno más nuevo).
- **Consumidores idempotentes**: tabla `mensajes_procesados` (id del evento) en el esquema del consumidor.
- Formato del mensaje: JSON con sobre común `{id, tipo, version, ocurrido, origen, traza, datos}`. Los esquemas de eventos se documentan en `contratos/eventos/*.yaml` (AsyncAPI o JSON Schema) junto a los OpenAPI.
- Comandos con respuesta inmediata (por ejemplo enviar el código MFA) **no** van por mensajería: son llamadas HTTP síncronas con Resilience4j (ADR-009). La mensajería es para hechos ya ocurridos.
- **Un usuario de RabbitMQ por servicio**, con escritura solo en su propio exchange. El consumidor valida que `origen` coincida con el exchange del que llega (evita que un servicio publique auditoría en nombre de otro).
  - Implementado en `infra/rabbitmq/` (2026-11-06): los exchanges `<origen>.eventos` (topic, durable, `alternate-exchange <origen>.sin-ruta`) los declara la infraestructura al arrancar y cada publicador los vuelve a declarar con los mismos argumentos; el consumidor no declara exchanges ajenos, solo sus colas `<servicio>.<cola>` (+ `.reintento`, `.dlq`, `.dlx` propio) y el binding.
  - **Matriz de lectura por tema** (`set_topic_permissions`): sin ella cualquier servicio podría enlazar `seguridad.eventos` con `#` y recibir las fotos de usuario con documento y correo (Ley 1581; lo detectó seguridad). Todo lo no listado está cerrado:

    | Exchange | Lee | Claves |
    |---|---|---|
    | `seguridad.eventos` | gateway | `sesion.*`, `usuario.permisos_cambiados`, `usuario.estado_cambiado` |
    | `seguridad.eventos` | visitas, reportes | `usuario.*`, `cuenta.*` |
    | `visitas.eventos` | reportes | `visita.*`, `hallazgo.*` |
    | `visitas.eventos` | notificaciones | `alerta.*`, `aviso.*` |
    | `visitas`/`reportes`/`notificaciones.eventos` | seguridad | `auditoria.*` |

  - Convención de claves: lo que debe notificarse a una persona sale como `aviso.<tipo>` (p. ej. `aviso.solicitud_nueva`) y lo crítico como `alerta.<tipo>`; la auditoría como `auditoria.<tipo>`. Una clave o un consumidor nuevo se pide a la integradora, que amplía la matriz.
  - Tope de mensaje 256 KiB (`max_message_size`). TLS de AMQP en el cierre (I9).
- Los mensajes no llevan secretos en claro (códigos, enlaces, contraseñas) ni datos personales que no se necesiten.

## Consecuencias
- (+) No se pierden eventos si RabbitMQ cae: quedan en el outbox.
- (+) La auditoría y las notificaciones no frenan la transacción de negocio.
- (−) Entrega «al menos una vez»: obliga a la idempotencia en todos los consumidores (se prueba en cada servicio).
- (−) Una pieza más de infraestructura que vigilar (métricas de colas y DLQ en Grafana).
