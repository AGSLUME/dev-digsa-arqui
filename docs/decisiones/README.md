# Decisiones de arquitectura (ADR)

Fase I0 de la integradora. Cada ADR ocupa una página. Estado: **Aceptados** por el usuario el 2026-11-05. Revisados por los agentes `seguridad` y `arquitectura`/`microservicios`/`infraestructura-operacion`; sus hallazgos verificados contra el código del monolito ya están incorporados.

| ADR | Tema | Decisión en una línea |
|---|---|---|
| [001](ADR-001-monorepo.md) | Repositorio | Monorepo Maven multi-módulo + `frontend/` Angular; un worktree y una rama por sesión |
| [002](ADR-002-servicios-grano-grueso.md) | Servicios | gateway, seguridad, visitas, reportes, notificaciones; `plataforma` y el marco de reportes van a `librerias/comun`, `exportacion` a visitas; sin servicio «identidad» aparte |
| [003](ADR-003-gateway-bff.md) | Entrada | Spring Cloud Gateway como BFF: tokens en el servidor, cookie HttpOnly, TokenRelay, sesión en Redis |
| [004](ADR-004-identidad.md) | Identidad | Spring Authorization Server dentro de `seguridad`; MFA y recuperación por correo como hoy |
| [005](ADR-005-datos.md) | Datos | Un esquema y un usuario por servicio en PostgreSQL 16; Flyway por servicio; Testcontainers |
| [006](ADR-006-mensajeria.md) | Mensajería | RabbitMQ + outbox transaccional + consumidores idempotentes |
| [007](ADR-007-contratos.md) | Contratos | OpenAPI 3.1 en `contratos/`, interfaces Spring y cliente Angular generados |
| [008](ADR-008-convivencia-monolito.md) | Convivencia | El gateway enruta analítica y presupuesto al monolito, que valida el mismo JWT (parche S4) |
| [009](ADR-009-resiliencia-observabilidad.md) | Operación | Resilience4j, Redis de caché, Prometheus/Grafana, Zipkin, logs JSON; Vault y K8s en el cierre |
| [010](ADR-010-puertos-entorno-local.md) | Puertos | Tabla base + desfase por sesión, verificada sin choques |
| [011](ADR-011-frontend-angular.md) | Frontend | Angular LTS standalone, design system LUME como tokens CSS, e2e Playwright portados |
| [012](ADR-012-versiones-cadena-suministro.md) | Versiones | Java 21, Spring Boot 4.x vigente, imágenes oficiales con digest, versiones exactas |
| [013](ADR-013-libreria-componentes.md) | Componentes | Solo Angular CDK + componentes LUME propios, ni Material ni PrimeNG (usuario, 2026-11-05) |
| [014](ADR-014-flujo-ingreso.md) | Ingreso | Pantalla Angular `/ingreso` + `/api/auth/*` como el monolito; termina en `/oauth2/authorize` con `continuar`; salir por `/bff/salir` |
