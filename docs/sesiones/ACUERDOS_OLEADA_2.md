# Acuerdos de la oleada 2 (integradora, 2026-11-06)

Leer junto con `docs/decisiones/` (ADR-001..014) y `librerias/comun/README.md`. Lo de aquí precisa los prompts de las sesiones.

## Un solo servicio `visitas`, dos sesiones

- **VISITAS A** es dueña del esqueleto: crea `servicios/visitas` (pom, aplicación, configuración, `contratos/visitas.yaml`, primeras migraciones). Puede agregar en su rama la línea `<module>servicios/visitas</module>` al `pom.xml` padre; es la única línea del pom padre que puede tocar. Cualquier otra versión o plugin se pide a la integradora.
- **VISITAS B** abre su sesión ya, pero **no escribe código del servicio hasta que VA1 esté en `develop`**. Mientras tanto: lee el monolito (explorador-codigo), inventaría pantallas, reglas y e2e, y manda su plan.
- **Paquetes**: A = `catalogos`, `maestros`, `agenda`. B = `ejecucion`, `hallazgos`, `opl`, `tablero` y, además, **`exportacion`** (actas, firmas, soportes, ZIP; ADR-002 la pone en `visitas` y la usa `hallazgos`).
- Paquetes Java separados por área (`co.com.agsamericas.digsa.visitas.<area>`) para no pisarse. Lo compartido del servicio (configuración, seguridad, outbox) es de A; B pide cambios por la integradora.

## Flyway (ADR-005)

- **VISITAS A: V1–V199. VISITAS B: V200–V399.** Nadie usa números del otro rango.
- `spring.flyway.out-of-order=true` en el servicio `visitas` (las dos ramas migran el mismo esquema en paralelo). Cada migración es independiente del orden entre rangos: si una de B necesita una tabla de A, la tabla debe estar ya en `develop`.
- Modelo de dos roles (`visitas_ddl` para Flyway, `visitas` para la app) con `GRANT` explícito por tabla con `${rol_app}`. Las pruebas usan `infra/postgres/init/01-esquemas.sh` (ver el patrón de `servicios/seguridad`).

## Lo que ya existe y se reutiliza

- `librerias/comun`: errores RFC 7807 con los textos del monolito, servidor de recursos JWT (`/api/**` solo con token de persona; `/interno/**` con token de máquina y alcance `<servicio>:<operación>`, `aud=<servicio>`), auditoría, outbox (cuando llegue S2 de seguridad) y `UsuarioActual`.
- Usuarios y perfiles: **copia local alimentada por los eventos `usuario.*` de seguridad** (S2). Hasta que lleguen, semilla ficticia solo en el perfil `local` y en pruebas.
- Correos y alertas: **nunca se envían desde `visitas`**; se publican eventos para `notificaciones` (oleada 2, sesión reportes).
- Frontend: las pantallas van en `frontend/src/app/paginas/visitas/` según `frontend/README.md` («Agregar pantallas»), con el design system de `ui/` y el registro de rutas `rutas-modulos.ts` (cada sesión edita solo su bloque).
- Puertos: los de su desfase en `infra/.env` (ya generado; no regenerarlo).

## Entregas

Como en la oleada 1: plan aprobado antes de programar; por fase, hash + pruebas por módulo (contadas desde los `TEST-*.xml`) + `npm audit` completo si toca frontend + dudas. La integradora revisa con revisor-codigo y seguridad, verifica en limpio y hace el merge.
