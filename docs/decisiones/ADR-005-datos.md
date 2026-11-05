# ADR-005 · Datos: una base por servicio, Flyway por servicio

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
El monolito usa un solo PostgreSQL 16 con Flyway (42 archivos de migración, numerados hasta V92) y guarda los archivos (evidencias, actas, soportes) en disco local (`AlmacenLocal`), que usan siete módulos. Los servicios deben poder evolucionar su modelo y desplegarse sin coordinar a los demás.

## Decisión
- **Cada servicio es dueño exclusivo de sus datos.** Ningún servicio lee ni escribe tablas de otro; lo que necesita lo pide por API (ADR-007) o lo mantiene como copia local alimentada por eventos (ADR-006).
- Local y CI: **un PostgreSQL 16** (imagen oficial `postgres:16.x` con digest fijo), con **un esquema y un usuario por servicio**: `seguridad`, `visitas`, `reportes`, `notificaciones`, más `gateway` solo si llegara a necesitarlo. Cada usuario tiene permisos únicamente sobre su esquema (`REVOKE` en `public`).
  - **Dos roles por servicio** (D8, pedido de seguridad, 2026-11-05): `<svc>_ddl`, dueño del esquema, solo para Flyway (`spring.flyway.user`/`password`); y `<svc>`, el de la aplicación, sin DDL ni privilegios por defecto. Cada migración concede explícitamente lo que la aplicación necesita sobre cada tabla, con el placeholder de Flyway `${rol_app}` como el monolito (p. ej. la bitácora solo `SELECT, INSERT`). Razón: una inyección SQL o escritura indebida no puede alterar el esquema ni borrar la auditoría.
- El script de arranque `infra/postgres/init/` crea esquemas y usuarios a partir de variables del `.env`. Ninguna contraseña en el repo.
- Productivo (cierre): una base lógica por servicio, en el mismo clúster o separada según la carga. El cambio es de configuración, no de código, porque cada servicio ya está aislado por esquema y usuario.
- **Flyway por servicio**, en `servicios/<svc>/src/main/resources/db/migration`, con el historial en su propio esquema. Las migraciones nuevas empiezan en `V1`. Cuando se porta el modelo del monolito se conservan nombres de tablas y columnas para facilitar la paridad, y el número `Vnn` del monolito queda citado en el comentario.
- Pruebas de integración con **Testcontainers** sobre la misma imagen fija de PostgreSQL. El monolito usaba embedded-postgres; aquí Docker ya es requisito.
- **Copias alimentadas por eventos** (ADR-002, ADR-008): cada una tiene una carga inicial por API y una conciliación periódica por API, porque el outbox solo captura los cambios nuevos.
- **Archivos**: la interfaz `Almacen` va en `librerias/comun`, y cada servicio guarda solo sus archivos. En local, un volumen Docker por servicio. En el cierre, un almacén de objetos compatible con S3 con un prefijo por servicio, que se elige en I9. Los archivos del monolito se migran en I9 con verificación de hash (ya existe `ArchivoIntegridadFallida`).
- Datos de prueba y semilla: **solo ficticios**.
- Retención: el registro y el contenido de cada correo se guardan 1 año en `notificaciones`. La auditoría (`seguridad`) conserva el plazo que hoy aplica el monolito.

## Consecuencias
- (+) Cada servicio migra su esquema sin coordinar con los demás.
- (−) No hay `JOIN` entre dominios: los informes que cruzan visitas y usuarios se resuelven en `reportes` con APIs o con proyecciones alimentadas por eventos.
- (−) La migración de datos del monolito al nuevo modelo es un trabajo aparte del cierre (I9): exportación por esquema e importación con verificación de conteos.
