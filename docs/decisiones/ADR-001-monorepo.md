# ADR-001 · Monorepo Maven multi-módulo + Angular, con un worktree por sesión

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
Seis sesiones trabajan en paralelo sobre el mismo sistema. Necesitan compartir librerías (`comun`, `reglas`), contratos OpenAPI e infraestructura local sin publicar artefactos entre ellas, y la integradora debe poder correr todo con un solo comando.

## Decisión
Un solo repositorio `dev-digsa-arqui` con esta forma:

```
pom.xml                  padre Maven (BOM de versiones, plugins, perfiles)
mvnw / .mvn/             Maven Wrapper con versión fija
librerias/comun          errores RFC 7807, auditoría, seguridad del recurso, utilidades
librerias/reglas         reglas de negocio puras copiadas del monolito (sin Spring)
servicios/gateway        Spring Cloud Gateway (ADR-003)
servicios/seguridad      identidad + usuarios + auditoría (ADR-002, ADR-004)
servicios/visitas | reportes | notificaciones
servicios/hola           servicio de humo de la fase I1 (se borra al cerrar la oleada 2)
contratos/*.yaml         OpenAPI por servicio (ADR-007)
frontend/                Angular (ADR-011)
e2e/                     Playwright adaptado desde digsa-java/e2e
infra/                   docker-compose, Prometheus/Grafana, scripts
docs/decisiones | docs/sesiones
.github/workflows/       CI (build + pruebas)
```

- Ramas: `main` (lo publicado), `develop` (integración; solo la integradora hace merge) y una rama por sesión: `seguridad`, `angular-base`, `visitas-a`, `visitas-b`, `reportes`.
- Cada sesión trabaja en su propio `git worktree` (`C:\Users\dgonzalez\dev-digsa-arqui-<sesion>`). La integradora reparte `develop` a las ramas después de cada merge.
- Un módulo Maven no depende de otro servicio, solo de `librerias/*`. Los servicios se hablan por HTTP (contratos) o por mensajes (ADR-006), nunca por código de dominio compartido.
- `.claude/`, `.env`, `target/` y `node_modules/` no se versionan.

## Consecuencias
- (+) Un `mvnw verify` y un `ng build` validan el conjunto; el contrato y su consumidor cambian en el mismo merge.
- (+) Los worktrees evitan seis clones y comparten historial.
- (−) El build completo crece; el CI construye por servicio con `-pl <modulo> -am` cuando haga falta.
- (−) Riesgo de conflictos en el `pom.xml` padre: solo la integradora lo modifica; las sesiones piden el cambio.
