# lume-comun

Piezas compartidas por los servicios Spring MVC de LUME. Gateway no la usa porque es reactivo. El monolito tampoco (ADR-008).

## Cadena de recurso (`seguridad/ConfiguracionRecurso`)

Al depender de `lume-comun`, todo servicio queda como **servidor de recursos** (ADR-003).

- La cadena se aplica **siempre** y va **de última**.
- `/api/**` exige un token de persona (claim `uid`).
- `/interno/**` exige un token de máquina (sin claim `uid`): un token de persona no entra aunque traiga el alcance. Cada endpoint pide su alcance con `@PreAuthorize("hasAuthority('SCOPE_<servicio>:<operación>')")`.
- Lo no declarado queda cerrado.
- Si un servicio necesita reglas propias (por ejemplo, rutas públicas), declara otra cadena con `securityMatcher(...)` y un `@Order` menor.

Validación del JWT en cada servicio:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${LUME_EMISOR}
  jwk-set-uri: ${LUME_SEGURIDAD_URL}/oauth2/jwks
  audiences: <servicio>          # audiencia por destinatario: cada servicio acepta solo tokens dirigidos a él
```

`ConvertidorJwt` arma las autoridades a partir del token:

- `PERFIL_<X>` sale **solo** de los perfiles directos.
- `PANTALLA_<X>` sale de las pantallas.
- `SCOPE_<x>` sale del `scope`, venga como texto o como lista JSON, que es como lo emite el servidor de autorización.

`UsuarioActual` da el usuario de la petición.

## Tokens de máquina (convención aprobada por la integradora, 2026-11-05)

- **Alcance:** `<servicio>:<operación>`. Ejemplos: `notificaciones:enviar_secreto`, `seguridad:sesiones`.
- **Audiencia:** `seguridad` emite el token con `aud = <servicio>`, uno por cada alcance. Un token de máquina solo sirve en el servicio dueño del alcance.
- **Obtención:** el cliente lo pide con `client_credentials` a `${LUME_SEGURIDAD_URL}/oauth2/token`. El cliente y sus alcances se registran en `seguridad`; se piden a la sesión seguridad.

## Base de datos: dos roles por servicio (D8, ADR-005)

El script `infra/postgres/init/01-esquemas.sh` crea dos roles por servicio:

| Rol | Uso | Permisos |
|---|---|---|
| `<svc>_ddl` | Solo Flyway (`spring.flyway.user` / `password`) | Dueño del esquema `<svc>` |
| `<svc>` | La aplicación (`spring.datasource.*`) | Solo `USAGE` sobre el esquema y los `GRANT` de cada migración |

Así se configura cada servicio:

```yaml
spring:
  datasource: { username: <svc>, password: ${<SVC>_DB_CLAVE}, hikari.schema: <svc> }
  flyway:
    user: <svc>_ddl
    password: ${<SVC>_DB_CLAVE_DDL}
    schemas: <svc>
    default-schema: <svc>
    create-schemas: false
    placeholders: { rol_app: <svc> }
```

Reglas para las migraciones:

- Cada migración termina con sus `GRANT ... TO ${rol_app}`, con lo mínimo:
  - sin `DELETE` donde el negocio no borra;
  - la bitácora, solo `SELECT, INSERT`.
- Las secuencias de las columnas identidad también necesitan su `GRANT USAGE`.

En las pruebas de integración, Testcontainers monta el **mismo** `01-esquemas.sh` con claves ficticias. Así, un `GRANT` faltante falla en la prueba igual que fallaría en local. Ejemplo: `servicios/seguridad/src/test/java/.../soporte/ContenedoresPrueba.java`.

## Errores (`errores/`)

`ErrorNegocio` y `ManejadorErrores` responden en RFC 7807 con `mensaje`, `campo` y `motivo` (`contratos/comun.yaml`). Los textos son los del monolito.

## Auditoría

Hoy `PublicadorAuditoria` deja el evento en el log (`PublicadorAuditoriaLog`). En S3 pasa a publicarse por el outbox de la sección siguiente.

## Eventos: outbox y consumo (`eventos/`, ADR-006)

### 1. Activarlo en un servicio

Dependencias del servicio (comun las trae como opcionales; así `hola` no cambia):

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-amqp</artifactId></dependency>
```

Configuración. Sin `lume.eventos.origen` no se crea nada:

```yaml
lume.eventos:
  origen: <servicio>            # el exchange propio es <servicio>.eventos (topic, durable)
  # cada-ms: 1000               # intervalo del despachador; 0 = apagado (pruebas: llaman a despachar() a mano)
  # lote: 100 · confirmacion-ms: 5000 · retener-dias: 7 · retener-procesados-dias: 30 · reintentos: 5
spring.rabbitmq:
  username: <servicio>          # un usuario de RabbitMQ por servicio, con escritura solo en su exchange
  password: ${<SVC>_RABBIT_CLAVE}
  publisher-confirm-type: simple   # obligatorio: sin esto el servicio no arranca
```

Tablas: se copia `src/main/resources/lume/eventos/outbox.sql` en una migración Flyway del servicio, con la versión que le toque en su rango. Crea `outbox` y `mensajes_procesados` con sus GRANT, que usan el placeholder `${rol_app}`.

### 2. Publicar

`PublicadorEventos.publicar(tipo, agregado, version, datos)` inserta en `outbox` **dentro de la transacción del cambio**. Sin transacción lanza `IllegalStateException`; si la transacción hace rollback, el evento no existió.

- `tipo`: `<agregado>.<hecho>` en minúsculas. Es la routing key.
- `version`: la versión del agregado, que lleva el propio servicio (por ejemplo una columna `version` que se sube con `UPDATE ... RETURNING`).
- `datos`: se serializa a JSON dentro del sobre `{id, tipo, version, ocurrido, origen, traza, datos}`.

`DespachadorOutbox` publica en segundo plano:

- toma lotes con `FOR UPDATE SKIP LOCKED`, así que varias instancias no publican la misma fila;
- espera la confirmación del broker y marca la fila;
- si el broker falla, reintenta con espera creciente hasta 300 s y nunca descarta;
- purga lo publicado cuando vence la retención.

La entrega es «al menos una vez».

### 3. Consumir eventos de otro servicio

```java
@Bean Declarables colaUsuarios() {   // <cola>, <cola>.reintento (TTL) y <cola>.dlq, todas quorum
    return Topologia.consumidor("visitas.usuarios", "seguridad.eventos", Duration.ofSeconds(30), "usuario.#", "cuenta.#");
}

@RabbitListener(queues = "visitas.usuarios")
void alRecibir(Message m) {
    consumo.procesar(m, "seguridad.eventos", sobre -> copia.aplicar(sobre));   // la acción va en la misma transacción
}
```

`ConsumoEventos.procesar`:

- valida el sobre y que `origen` corresponda al exchange; si no, el mensaje va a la DLQ;
- aplica cada `id` una sola vez (`mensajes_procesados`, en la transacción de la acción);
- si la acción falla, el mensaje pasa a `<cola>.reintento` y vuelve tras la espera; agotados `reintentos`, va a la DLQ.

Obligaciones de la acción (la copia):

- **Regla de versión, atómica.** Se aplica con un UPDATE o UPSERT condicional, por ejemplo `INSERT ... ON CONFLICT (uid) DO UPDATE ... WHERE copia.version < EXCLUDED.version`, nunca leyendo y luego escribiendo. Con varios hilos de escucha o varias instancias, el orden de llegada no está garantizado.
- **Lista blanca de tipos.** Hacer `switch` por `sobre.tipo()` y descartar los tipos desconocidos.
- **Sin datos en las excepciones.** La acción no pone datos del evento en sus mensajes de error. `ConsumoEventos` ya no propaga la causa al contenedor, pero los logs propios de la acción sí son responsabilidad del servicio.

Otros comportamientos de `ConsumoEventos`:

- Se rechaza, sin reintento, todo cuerpo de más de 256 KiB, todo tipo que no cumpla `<agregado>.<hecho>` y todo `datos` que no sea un objeto.
- Las caídas de la base (`TransientDataAccessException` y similares) nunca mandan el mensaje a la DLQ: se reintenta hasta que la base vuelva.

### Métricas

Si el servicio tiene Micrometer, se exponen dos métricas para la alerta de Grafana:

- `lume.outbox.pendientes`: eventos sin publicar.
- `lume.outbox.antiguedad`: segundos del pendiente más viejo.

Con el broker caído, las dos crecen.

### Eventos sin cola enlazada

El exchange propio lleva el alterno `<origen>.sin-ruta`. Un evento publicado cuando ningún consumidor tiene su cola enlazada queda en la cola `<origen>.sin-ruta` (hasta 100 000 mensajes), en vez de perderse. Un consumidor nuevo se pone al día con la exportación de conciliación de su productor (ADR-005) y, si hace falta, reprocesando esa cola.

### 4. Datos personales (Ley 1581)

El cuerpo de los eventos puede llevar datos personales, por ejemplo la foto de `usuario.*` con nombre, documento y correo. Por eso:

- ni el publicador, ni el despachador, ni el consumo escriben el cuerpo ni `datos` en los logs: solo id, tipo y cola;
- `Sobre.toString()` no muestra `datos`;
- cada consumidor guarda solo los campos que usa.

Los esquemas de cada servicio están en `contratos/eventos/<servicio>.yaml`.
