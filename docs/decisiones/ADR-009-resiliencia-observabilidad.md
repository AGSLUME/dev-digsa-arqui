# ADR-009 · Resiliencia, caché y observabilidad

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
La meta de disponibilidad es 99,74 % (unas 23 horas de caída al año). Con varios servicios, un fallo en uno no debe tumbar a los demás, y un error debe poder seguirse de punta a punta.

## Decisión
**Resiliencia (Resilience4j)**
- Toda llamada HTTP entre servicios lleva *timeout* explícito, *circuit breaker* y reintento solo en operaciones idempotentes (GET, o comandos con clave de idempotencia).
- El gateway aplica *circuit breaker* por ruta y responde con un `Problema` RFC 7807 (503) con un texto claro, nunca con una traza.
- *Bulkhead* en las llamadas a `notificaciones` y al monolito, para que su lentitud no agote recursos. En el gateway reactivo es de semáforo, no de hilos.
- Presupuesto de disponibilidad: gateway, Redis, `seguridad` y PostgreSQL forman una cadena en serie para cualquier petición, así que cada uno necesita más de 99,9 % para que el conjunto dé 99,74 %. En el cierre van con al menos dos réplicas (Redis y PostgreSQL en alta disponibilidad); el reparto exacto se documenta en I9.

**Caché (Redis)**
- Redis (imagen oficial con digest fijo) para: la sesión del gateway (ADR-003), el límite de tasa y la caché de lecturas frecuentes de otro servicio (maestros, catálogos, parámetros de seguridad) con TTL corto. No es una base de datos: nada que no pueda reconstruirse.
- La sesión del gateway guarda access y refresh tokens, así que Redis va endurecido: contraseña y usuarios ACL con permisos mínimos, sin puerto publicado fuera de la red de Docker (salvo `127.0.0.1` en local), sin persistencia en disco y con TLS en el cierre. Perder Redis cierra la sesión de todos los usuarios (vuelven a ingresar, sin perder datos): por eso entra en el presupuesto de disponibilidad.

**Observabilidad**
- Métricas: Micrometer → **Prometheus** → **Grafana** (un tablero base por servicio: latencia, errores, JVM, pool de BD, colas de RabbitMQ).
- Trazas: Micrometer Tracing con propagación W3C → **Zipkin**. El `traceId` viaja en las cabeceras HTTP y en el sobre de los mensajes (ADR-006).
- Logs: **JSON estructurado** por la salida estándar (logging estructurado de Spring Boot), con `traceId`, `spanId`, `servicio` y `usuario` (identificador, no nombre). Nunca contraseñas, códigos, tokens ni datos personales completos.
- Kibana (ELK) se incorpora en el cierre (I9) con Fluent Bit recogiendo esos mismos logs JSON; en local basta `docker compose logs`. En el cierre Zipkin necesita almacenamiento persistente; en local va en memoria.
- Salud: Actuator `health` con *liveness* y *readiness* (que serán las sondas de Kubernetes); `prometheus` solo en la red interna.

**Secretos y plataforma**
- Local: Docker Compose con `.env` (ignorado por git) y un `.env.ejemplo` versionado, sin valores reales.
- Cierre (I9): Vault para los secretos y Kubernetes para el despliegue. Hasta entonces, nada en el código depende de Vault ni de Kubernetes.

## Consecuencias
- (+) Cada fallo es visible (métrica + traza + log correlacionados) y queda acotado al servicio que falla.
- (−) Más contenedores en local: el compose tiene perfiles (`--profile obs`) para levantar la observabilidad solo cuando haga falta.
