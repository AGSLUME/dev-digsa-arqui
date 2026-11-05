# ADR-010 · Puertos y entorno local por sesión

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
Seis sesiones corren su propio entorno a la vez en el mismo equipo. digsa-java usa 8080/8081 y Mailpit ya está instalado (127.0.0.1:1025 SMTP, 8025 web).

## Decisión
Puerto = base + desfase de la sesión. Todo se publica solo en `127.0.0.1`.

| Componente | Base | integradora +0 | seguridad +100 | angular +200 | visitas-a +300 | visitas-b +400 | reportes +500 |
|---|---|---|---|---|---|---|---|
| gateway | 9000 | 9000 | 9100 | 9200 | 9300 | 9400 | 9500 |
| (reservado, ver ADR-004) | 9080 | 9080 | 9180 | 9280 | 9380 | 9480 | 9580 |
| seguridad | 9101 | 9101 | 9201 | 9301 | 9401 | 9501 | 9601 |
| visitas | 9102 | 9102 | 9202 | 9302 | 9402 | 9502 | 9602 |
| reportes | 9103 | 9103 | 9203 | 9303 | 9403 | 9503 | 9603 |
| notificaciones | 9104 | 9104 | 9204 | 9304 | 9404 | 9504 | 9604 |
| hola (humo I1, temporal) | 9109 | 9109 | 9209 | 9309 | 9409 | 9509 | 9609 |
| monolito (convivencia) | 9050 | 9050 | 9150 | 9250 | 9350 | 9450 | 9550 |
| Angular (+desfase/10) | 4200 | 4200 | 4210 | 4220 | 4230 | 4240 | 4250 |
| PostgreSQL (+desfase/100) | 5440 | 5440 | 5441 | 5442 | 5443 | 5444 | 5445 |
| RabbitMQ AMQP | 5672 | 5672 | 5772 | 5872 | 5972 | 6072 | 6172 |
| RabbitMQ consola | 15672 | 15672 | 15772 | 15872 | 15972 | 16072 | 16172 |
| Redis (+desfase/100) | 6380 | 6380 | 6381 | 6382 | 6383 | 6384 | 6385 |
| Prometheus | 9090 | 9090 | 9190 | 9290 | 9390 | 9490 | 9590 |
| Grafana | 3000 | 3000 | 3100 | 3200 | 3300 | 3400 | 3500 |
| Zipkin | 9411 | 9411 | 9511 | 9611 | 9711 | 9811 | 9911 |

- Comprobado: ningún puerto se repite entre sesiones ni coincide con 8080/8081, 1025/8025 o el PostgreSQL local por defecto (5432).
- Redis arranca en 6380 y no en 6379 para no chocar con un Redis local que ya exista.
- `infra/docker-compose.yml` recibe `DESFASE` y los puertos calculados desde el `.env` de cada worktree (`infra/.env.ejemplo` trae la tabla). Se ejecuta siempre con nombre de proyecto propio: `docker compose -p digsa-arqui-<sesion> --env-file .env up`.
- Los servicios Spring leen su puerto y sus URL de variables de entorno (`SERVER_PORT`, `DIGSA_*`); ningún puerto va fijo en el código.
- Correo en local: todos los servicios usan el Mailpit ya instalado (`host.docker.internal:1025` desde contenedores, `127.0.0.1:1025` desde el host). No se levanta otro. Como el buzón es compartido, **los datos ficticios de cada sesión usan un dominio propio** (`@<sesion>.prueba.local`) y los e2e que leen el código MFA o el enlace filtran por destinatario en la API de Mailpit.
- Instancia del monolito para probar la convivencia (S4 y el cierre): puerto **9050 + desfase**, nunca 8080 u 8081; su URL va en `DIGSA_MONOLITO_URL`.
- Memoria: cada servicio corre en local con `-Xmx384m` (ajustable en `.env`); cada sesión levanta solo los servicios que necesita.

## Consecuencias
- (+) Las seis sesiones pueden tener todo arriba a la vez.
- (−) Memoria: seis entornos completos pesan; la observabilidad va en un perfil opcional (ADR-009) y cada sesión levanta solo lo que necesita.
