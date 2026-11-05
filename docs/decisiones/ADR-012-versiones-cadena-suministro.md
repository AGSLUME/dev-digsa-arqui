# ADR-012 · Versiones de la plataforma y cadena de suministro

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
El monolito usa Java 21 y Spring Boot 3.5.16. La regla del proyecto exige fuente oficial, versión fija y digest o firma verificados para todo lo que se instala, e imágenes Docker solo oficiales o de editor verificado.

## Decisión
**Plataforma Java**
- **Java 21 LTS** (igual que el monolito; las reglas copiadas compilan sin cambios).
- **Spring Boot: la última versión estable de la línea 4.x con soporte OSS vigente**, confirmada en I1 en spring.io/projects/spring-boot#support. Razón para no quedarse en 3.5 como el monolito: un sistema nuevo no debe nacer sobre una línea cuyo soporte OSS termina o ya terminó. Spring Cloud (Gateway) y Spring Authorization Server en las versiones compatibles con esa línea, según la tabla oficial.
  - Si en I1 la compatibilidad de Spring Cloud con 4.x no estuviera publicada como estable, se usa 3.5.x y se deja registrado aquí. Lo decide la integradora sin volver a preguntar.
  - Impacto conocido al portar código del monolito: Jackson 3 y Spring Security 7 cambian algunos paquetes y APIs. Las reglas puras (`librerias/reglas`) no se ven afectadas.
  - El monolito que convive sigue en Boot 3.5 (fuera de nuestro alcance actualizarlo); su parche de convivencia no comparte código con `librerias/comun` (ADR-008).
- Todas las versiones en el `pom.xml` padre (propiedades + BOM). Ningún módulo declara una versión suelta. Maven Wrapper con la suma SHA-256 de la distribución en `maven-wrapper.properties`.

**Fijado en I1 (2026-11-05), verificado en Maven Central, start.spring.io, Docker Hub y npm**

| Pieza | Versión |
|---|---|
| Spring Boot | 4.1.2 (soporte OSS hasta 2027-07-31) |
| Spring Cloud | 2025.1.4 (compatible con Boot >=4.0.0 <4.2.0-M1); Gateway 5.0.4 |
| Spring Security / Authorization Server | 7.1.2 |
| Maven | 3.9.16 vía wrapper 3.3.4, SHA-256 verificado contra el SHA-512 oficial |
| PostgreSQL / RabbitMQ / Redis | 16.15 / 4.3.7-management / 8.10.3, con digest |
| Prometheus / Grafana / Zipkin (perfil `obs`) | v3.16.0 / 13.2.5 / 3.6.1, con digest; Prometheus y Zipkin pendientes de aprobación (no son oficiales ni de editor verificado en Docker Hub) |
| Angular / Node | 21.2 (CLI 21.2.29) / 24.19.0 |
| Acciones de GitHub | checkout v7.0.1, setup-java v6.2.0, setup-node v7.1.0, fijadas por SHA |

**Imágenes Docker**
- Solo oficiales de Docker Hub (`postgres`, `rabbitmq`, `redis`, `eclipse-temurin`) o de editor verificado (`openzipkin/zipkin`, `prom/prometheus`, `grafana/grafana`).
- Siempre con etiqueta exacta **y** digest: `postgres:16.x@sha256:...`. El digest se obtiene del registro oficial y queda escrito en el compose.

**npm y herramientas**
- Versiones exactas (sin `^` ni `~`) y `package-lock.json` versionado; `npm ci` en CI.
- Herramientas del CI (generador OpenAPI, linters) con versión fija; acciones de GitHub fijadas por SHA del commit, no por etiqueta.

**Verificación**
- CI: análisis de dependencias (OWASP Dependency-Check o `npm audit` / `mvn` equivalente) como aviso desde I1 y como bloqueo para vulnerabilidades críticas desde el cierre.
- Antes de añadir una dependencia nueva, la sesión la justifica en su entrega y la integradora la aprueba en el merge.

## Consecuencias
- (+) Builds reproducibles e imágenes inmutables.
- (−) Actualizar exige cambiar versión y digest a propósito; se hace en un commit propio de la integradora.
