# lume-comun

Piezas compartidas por los servicios Spring MVC de LUME. Gateway no la usa porque es reactivo. El monolito tampoco (ADR-008).

## Cadena de recurso (`seguridad/ConfiguracionRecurso`)

Al depender de `lume-comun`, todo servicio queda como **servidor de recursos** (ADR-003).

- La cadena se aplica **siempre** y va **de última**.
- `/api/**` exige un token de persona (claim `uid`).
- `/interno/**` exige un token válido. Cada endpoint pide su alcance con `@PreAuthorize("hasAuthority('SCOPE_<servicio>:<operación>')")`.
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

## Auditoría y outbox

- Hoy `PublicadorAuditoria` deja el evento en el log (`PublicadorAuditoriaLog`).
- El **outbox transaccional** (ADR-006) y su despachador (`FOR UPDATE SKIP LOCKED`) llegan a esta librería en S2 y S3. Será la pieza compartida para publicar eventos y auditoría desde todos los servicios, y esta sección documentará cómo usarlo.
