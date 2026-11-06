# visitas

Servicio de visitas (ADR-002). Lo construyen dos sesiones (`docs/sesiones/ACUERDOS_OLEADA_2.md`):

- **visitas-a**: `catalogos`, `maestros`, `agenda`, `usuarios` (copia local) y lo compartido (`compartido`, configuración, esquema).
- **visitas-b**: `ejecucion`, `hallazgos`, `opl`, `tablero`, `exportacion`.

Paquetes Java: `co.com.agsamericas.digsa.visitas.<area>`. Flyway: A usa V1–V199 y B V200–V399 (`infra/ci/rangos-flyway.sh`).

## Arranque local

```bash
docker compose -p digsa-arqui-<sesion> --env-file infra/.env -f infra/docker-compose.yml up -d   # PostgreSQL, Redis, RabbitMQ
bash infra/arrancar.sh visitas                                                                   # perfil local, puerto VISITAS_PUERTO
```

En el perfil `local` los archivos van a `LUME_ARCHIVOS_VISITAS` (por omisión, la carpeta temporal) y Flyway tolera las migraciones de la
otra rama (`ignore-migration-patterns: "*:missing"`).

## Esquema (lo que A deja para todos)

| Migración | Tablas |
|---|---|
| V1 | `par_departamentos`, `par_municipios`, `par_unidades`, `par_fuerzas`, `par_regionales`, `par_modalidades`, `par_tipos_visita`, `par_parametros`, `par_festivos`, `catalogos`, `catalogo_valores` |
| V2 | `usuarios_copia`, `mensajes_procesados` |
| V3 | `opl`, `puntos`, `auditores`, `obligaciones_contrato` |
| V4, V5 | `chk_versiones`, `chk_vacios`, `chk_guia_obligacion`, `chk_componentes`, `chk_items` (v6 vigente) |
| V6 | Datos iniciales editables (fuerzas, catálogos, regionales, 185 puntos) |
| V7 | `visitas` (todas sus columnas, también las que escribe ejecución), `visitas_componentes`, `visitas_auditores`, `visitas_reparto`, `visitas_historial`, `visitas_notificaciones` |
| V8 | `archivos` (evidencias, acta, obligaciones) |
| V9 | Tipos de visita y parámetros (se insertan antes que cualquier V200+) |
| R__catalogos | Actualización idempotente de la geografía, tipos y parámetros |

Reglas: los usuarios no tienen FK (son copia de `seguridad`); `visitas_historial` solo admite `SELECT` e `INSERT`; las visitas no se borran.

## Interfaz interna para VISITAS B

Mismo servicio y esquema: B puede leer las tablas con SQL y llamar a estas clases. Nada de esto sale por HTTP.

| Clase | Para qué |
|---|---|
| `agenda.RepositorioVisitas` | El `RepositorioVisitas` del monolito con los mismos métodos y records (`Visita`, `Miembro`, …). `bloquear(id)` usa `FOR NO KEY UPDATE`: **en toda transacción que cambie una visita, primero se bloquea la visita y después sus tablas hijas**. Estados con `cambiarEstado(id, origen, destino)` y las reglas de `reglas/EstadosVisita` (p. ej. cerrar = `ejecucion → cerrada`). `reabrir(id)` = `cerrada → ejecucion` con `reaperturas + 1`. `historial(...)` escribe en `visitas_historial`. |
| `agenda.AlcanceVisitas.condicion(Alcance)` | Condición SQL del alcance sobre `v` (visitas) y `p` (puntos), con parámetros `alc_*`. |
| `compartido.AlcanceDelToken.de(UsuarioActual)` | El `reglas.Alcance` del token (reemplaza `UsuarioSesion.alcanceActual()`). Falla cerrado. |
| `agenda.PlazosAgenda.plazos()` | `reglas.Plazos` con parámetros y festivos de la base (caché de 30 s). |
| `agenda.AgendaParaEjecucion` | Firmas de `programarSeguimiento`, `notificarSeguimiento`, `cancelarSeguimientoPendiente`, `iniciar` y `calcularDestinatarios`. **El bean llega en VA2**; desde VA1 se puede compilar contra la interfaz. |
| `usuarios.DirectorioUsuarios` | Usuarios de la copia: `porId`, `auditorActivo`, `esAuditor`, `auditores`, `correosCoordinadoresActivos`, conteos por alcance. El documento es dato personal: nunca a logs ni a la bitácora. |
| `compartido.BitacoraVisitas.registrar(tipo, detalle)` | Evento de negocio de la bitácora con los tipos de `reglas/Bitacora` (por `PublicadorAuditoria`). La fila por petición la pone el filtro de `comun` (S3). |
| `catalogos.ServicioCatalogos.entero / textoParametro` | Lectura de parámetros (`correo_digsa`, `max_seguimientos`, …). |
| `maestros.UsoObligaciones` | **B la implementa** (bean): `calificada(obligacionId)` sobre `visitas_obligaciones`. Sin ella, maestros entiende que ninguna se calificó. |

Archivos: `Almacen` y `ValidadorArchivos` de `librerias/comun` (carpeta `lume.archivos.carpeta`).

## Mensajería (RabbitMQ, ADR-006; integradora, develop 7def773)

- `visitas` publica **solo** en el exchange `visitas.eventos`: tipo `topic`, durable y con `{"alternate-exchange": "visitas.sin-ruta"}`.
  Ya existe desde el arranque del broker; quien lo declare usa exactamente esos argumentos. No se declaran exchanges ajenos.
- Claves que se pueden publicar (matriz de lectura por tema). Cualquier otra no la lee nadie hasta que la integradora la agregue a la matriz:

  | Clave | Lo lee | Para qué |
  |---|---|---|
  | `aviso.<tipo>` (p. ej. `aviso.solicitud_nueva`, `aviso.solicitud_agendada`, `aviso.solicitud_cancelada`) | notificaciones | Lo que debe notificarse a una persona |
  | `alerta.<tipo>` | notificaciones | Lo crítico (correo y SMS) |
  | `visita.*`, `hallazgo.*` | reportes | Proyección de reportes |
  | `auditoria.<tipo>` | seguridad | Bitácora |

- `visitas` lee de `seguridad.eventos` las claves `usuario.*` y `cuenta.*`. Sus colas se llaman `visitas.<algo>` (la copia de usuarios va en
  `visitas.usuarios`); se declaran la cola y el binding, nada más. Usuario del broker: `VISITAS_RABBIT_USUARIO` / `VISITAS_RABBIT_CLAVE`.
- Topología y consumo con `Topologia` y `ConsumoEventos` de `librerias/comun` (unidad S2-OUTBOX de seguridad). En las pruebas, el RabbitMQ
  de Testcontainers monta `infra/rabbitmq/usuarios.sh` y se usa el usuario del servicio, no el administrador.

## Pruebas

`PruebaIntegracion` (Testcontainers con `infra/postgres/init/01-esquemas.sh`, perfil `prueba`, `RelojMovible`, `AuditoriaCapturada`) y
`PruebaApi` (peticiones con token, siembra de usuarios en la copia). El token de prueba arma las autoridades con el `ConvertidorJwt` real.
La copia de usuarios se siembra en local y en e2e con `POST /interno/pruebas/usuarios` (solo perfiles `local` y `prueba`, alcance
`visitas:pruebas`), hasta que lleguen los eventos `usuario.*` de seguridad.
