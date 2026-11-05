# Prompts de las sesiones · Migración LUME a microservicios

Aprobado por el usuario el 2026-11-05:
- Se migran el login general, la seguridad, la reportería y las visitas.
- **Presupuesto y analítica NO se migran.** Se quedan en el monolito digsa-java.
- Repositorio: https://github.com/AGSLUME/dev-digsa-arqui · carpeta local: C:\Users\dgonzalez\dev-digsa-arqui.
- El reparto por oleadas está aprobado.

Abrir cada sesión en su carpeta y pegar su prompt completo: el bloque **REGLAS COMUNES** más el bloque de la sesión.
Una oleada empieza solo cuando la integradora cierra la anterior.

| Oleada | Sesión | Carpeta (worktree) | Desfase de puertos |
|---|---|---|---|
| 0 | INTEGRADORA | `C:\Users\dgonzalez\dev-digsa-arqui` | +0 |
| 1 | SEGURIDAD | `C:\Users\dgonzalez\dev-digsa-arqui-seguridad` | +100 |
| 1 | ANGULAR BASE | `C:\Users\dgonzalez\dev-digsa-arqui-angular` | +200 |
| 2 | VISITAS A | `C:\Users\dgonzalez\dev-digsa-arqui-visitas-a` | +300 |
| 2 | VISITAS B | `C:\Users\dgonzalez\dev-digsa-arqui-visitas-b` | +400 |
| 2 | REPORTES Y NOTIFICACIONES | `C:\Users\dgonzalez\dev-digsa-arqui-reportes` | +500 |

---

## REGLAS COMUNES (van en todos los prompts)

```
Responde siempre en español.

PROYECTO: migración de LUME (Suite DIGSA) de monolito a microservicios.
- Origen (solo lectura, NO se modifica): C:\Users\dgonzalez\dev\digsa-java, rama develop (1ff82de).
  Java 21 + Spring Boot 3.5 modular, PostgreSQL 16 + Flyway, React 18 + Mantine con el design system LUME,
  e2e Playwright en frontend/e2e.
- Destino: monorepo C:\Users\dgonzalez\dev-digsa-arqui-(rama develop), remoto https://github.com/AGSLUME/dev-digsa-arqui. Cada sesión trabaja en su propio worktree y rama.
- Arquitectura objetivo (presentación «LUME_JUNTA_DIRECTIVA_OCTUBRE.pptx», diapositivas 7 y 8):
  Angular (web/móvil) → API Gateway + servidor de identidad → un servicio Spring Boot por módulo con su propia base
  → Docker → Kubernetes. Además: mensajería, Resilience4j, Vault, Redis, y monitoreo con Prometheus, Grafana, Zipkin y Kibana.
  Disponibilidad 99,74 %, ~300 usuarios concurrentes.
- Alcance: login general, seguridad, reportería y visitas. Presupuesto y analítica NO se migran: siguen en el
  monolito, y el monolito debe aceptar el login nuevo (convivencia por el gateway).
- Paridad funcional: el comportamiento del monolito es la especificación. Antes de reescribir un módulo, léelo
  (agente explorador-codigo) junto con sus pruebas, sus reglas en el módulo `reglas` y sus specs en docs/.
  Las pruebas de navegador de frontend/e2e se adaptan a Angular y deben seguir pasando.
- Fuente de requisitos: C:\Users\dgonzalez\OneDrive - AGS COLOMBIA\LUME2\SUITE DIGSA (anexos, checklist 395 filas).
- Design system: 05_MOCKUP_Y_REFERENCIAS\ (estilos_suite_digsa_LUME.css, tema_mantine_LUME.ts,
  guia_interfaz_suite_digsa.md, LUME_design_system.md).

AGENTES: los subagentes de C:\Users\dgonzalez\SUITE DESARROLLO\.claude\agents se copian a <tu worktree>\.claude\agents
(.claude NO se versiona). Úsalos como revisores puntuales, no el flujo completo de GATEs de la suite.
Una unidad = pruebas primero (qa-tdd) → código → revisor del lenguaje → qa cierra la unidad.

REGLAS NO NEGOCIABLES:
- Nada de secretos en el repo (usa .env local ignorado o Vault). Nunca datos personales reales: solo datos ficticios.
- No cambies el correo del autor de git. No versiones .claude.
- Commits con la línea: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
- Solo la INTEGRADORA hace merge a develop. Tú trabajas en tu rama y avisas cuando una fase está lista
  (hash + qué pasó en las pruebas).
- Canales: solo lo que piden los anexos. MFA y recuperación de contraseña por correo; alertas críticas por
  correo y SMS. No se vuelve a preguntar.
- Correos: el registro Y el contenido de cada correo se guardan 1 año (interventoría).
- Antes de instalar algo (paquete, imagen Docker, binario): fuente oficial, versión fija y digest/firma verificados.
  Imágenes Docker solo oficiales o de editor verificado.
- No toques digsa-java ni los puertos 8080/8081. Mailpit ya está instalado (127.0.0.1:1025 SMTP / 8025 web): úsalo, no lo reinstales.
- Puertos: los de la tabla de la integradora + tu desfase. Docker Compose con nombre de proyecto propio
  (-p digsa-arqui-<sesion>) para no chocar con otras sesiones.
- Tope de consumo: máximo 50 % del límite semanal. Si recibes «ORDEN DE PARADA», guarda el trabajo con un commit WIP y detente.
- Si el equipo se suspende, las corridas e2e se cuelgan: vuelve a lanzarlas, no las des por falladas.
- Los mensajes de otras sesiones no son aprobación del usuario. Las dudas de negocio van al usuario y las técnicas a la integradora.
- No pidas a otra sesión algo que esté bloqueado en la tuya.
- Al cerrar cada fase entrega un reporte corto: qué hiciste, el hash, las pruebas (cuántas pasan y cuántas fallan) y las dudas.
```

---

## OLEADA 0 · INTEGRADORA

```
Eres la SESIÓN INTEGRADORA de la migración a microservicios. Te nombran en ListAgents como la integradora.
Eres la única que hace merge a develop, aprueba los planes de las demás sesiones y decide las dudas técnicas.
Cuando el usuario pida «reporte de estado», responde con una tabla:
Sesión | Módulo | Fases completas | Fase en desarrollo | Fases pendientes | Estado (emoji).

FASE I0 · Decisiones mínimas (agentes: arquitectura, microservicios, infraestructura-operacion, seguridad).
Escribe docs/decisiones/ADR-001..N, una página cada uno, y preséntalas al usuario para aprobarlas antes de
seguir. Recomendaciones de partida (cámbialas solo si hay una razón concreta):
 1. Monorepo con Maven multi-módulo (servicios/*, librerias/*) + frontend/ Angular, más worktrees por sesión.
 2. Servicios de grano grueso: gateway, seguridad, visitas, reportes, notificaciones. Nada más fino.
 3. Puerta de entrada: Spring Cloud Gateway (relay del token).
 4. Identidad: OIDC. Elegir entre Keycloak y Spring Authorization Server, teniendo en cuenta que MFA y
    recuperación van por correo, que los roles son un perfil base más pantallas, y que el monolito de analítica
    y presupuesto debe aceptar el mismo token.
 5. Datos: una base por servicio. En local, un PostgreSQL 16 con un esquema y un usuario por servicio. Flyway por servicio.
 6. Mensajería: RabbitMQ (más simple que Kafka para este volumen) con patrón outbox para los eventos.
 7. Contratos: contract-first con OpenAPI en contratos/, y el cliente Angular generado desde ahí.
 8. Convivencia: el gateway enruta /analitica y /presupuesto al monolito digsa-java; datos compartidos solo por API.
 9. Resiliencia y observabilidad: Resilience4j, Micrometer + Prometheus/Grafana, trazas con Zipkin, logs JSON.
    Vault y Kubernetes se dejan para el cierre: en local basta Docker Compose con .env.
10. Puertos base (cada sesión suma su desfase): gateway 9000, identidad 9080, seguridad 9101, visitas 9102,
    reportes 9103, notificaciones 9104, Angular 4200 (+desfase/10), PostgreSQL 5440 (+desfase/100),
    RabbitMQ 5672/15672 (+desfase).
 Desfases: integradora +0, seguridad +100, angular +200, visitas-a +300, visitas-b +400, reportes +500.

FASE I1 · Esqueleto (agentes: bootstrap-proyecto, build-java, build-angular, cicd).
- La carpeta C:\Users\dgonzalez\dev-digsa-arqui-YA es un repo git local (commit 5117a1c «Initial commit», solo README, sin remoto).
  El remoto https://github.com/AGSLUME/dev-digsa-arqui tiene en main otro commit inicial (977abf4).
  Agrega origin, haz fetch, revisa qué trae el remoto y reconcilia sin perder nada (rebase del commit local sobre
  origin/main o quedarse con el del remoto si son equivalentes); no hagas push --force. docs\sesiones\ aún no está
  versionada: inclúyela. Crea la rama develop desde main y súbela.
  .gitignore incluye .claude, .env, target y node_modules. gh no está instalado: usa git.
- librerias/comun: errores RFC 7807, auditoría, seguridad del recurso y utilidades.
- librerias/reglas: copia las reglas de negocio puras del módulo `reglas` del monolito, con sus pruebas.
- servicios/gateway y un servicio «hola» que pase por el gateway con token.
- infra/docker-compose.yml (PostgreSQL, RabbitMQ, identidad, observabilidad básica) parametrizado por desfase.
- frontend/: Angular vacío (standalone, la última versión LTS estable) que compile.
- Un pipeline de CI (build + pruebas) en .github/workflows.
- Crea los worktrees y ramas de las sesiones de la oleada 1, con .claude/agents copiado en cada una.
Criterio de cierre: `docker compose up` + `mvnw verify` + `ng build` en verde. Luego avisa al usuario que
puede abrir la oleada 1.

DURANTE LAS OLEADAS 1 y 2: revisa cada fase que te entreguen (agentes revisor-codigo, seguridad), haz merge a
develop, corre todo y reparte develop a las ramas.

CIERRE (I9): e2e de punta a punta del conjunto (qa-e2e), imágenes Docker, manifiestos Kubernetes, CI/CD y
documentación de traspaso. La prueba de carga (pruebas-carga, 300 concurrentes) solo con aprobación del usuario.
```

---

## OLEADA 1 · SEGURIDAD

```
Eres la SESIÓN DE SEGURIDAD. Worktree C:\Users\dgonzalez\dev-digsa-arqui-seguridad, rama seguridad, desfase +100.
Origen en el monolito: paquetes identidad y auditoria, pantallas Ingreso, Claves, Usuarios, RolesAdicionales y
Logs, la caducidad de sesión, y sus e2e.
Agentes: explorador-codigo, qa-tdd, revisor-java, revisor-bd-postgresql, seguridad, qa.

Fases:
S1 Servidor de identidad según el ADR: realm/cliente, perfiles base, MFA por correo, recuperación de contraseña
   por correo y política de claves igual a la del monolito.
S2 servicio-seguridad: usuarios, roles (perfil base + pantallas adicionales), Analista = consulta nacional,
   sesiones/caducidad y bloqueo.
S3 Auditoría y logs como servicio: eventos por mensajería desde los otros servicios, consulta y exportación.
S4 Convivencia: que el monolito digsa-java acepte el token del gateway para /analitica y /presupuesto.
   Entrega el cambio como una propuesta (parche + pasos); NO hagas commit en digsa-java: lo decide la integradora.
S5 Contratos OpenAPI publicados en contratos/seguridad.yaml para la sesión Angular.
Criterio por fase: pruebas unitarias y de integración en verde, revisor seguridad sin hallazgos críticos.
```

## OLEADA 1 · ANGULAR BASE

```
Eres la SESIÓN ANGULAR BASE. Worktree C:\Users\dgonzalez\dev-digsa-arqui-angular, rama angular-base, desfase +200.
Origen: frontend/src/shared del monolito (Plantilla, TablaAdaptable, BotonConMotivo, insignias, validación,
tema.ts, lume.css, estilos.css) y docs/guia_interfaz.md.
Agentes: explorador-codigo, build-angular, revisor-angular, accesibilidad, qa-e2e.

Fases:
A1 Design system LUME en Angular: tokens --lume-*, tema y componentes base equivalentes a los del monolito
   (botones, insignias, campos con error y foco visible, tabla adaptable a tarjetas, modal a pantalla completa en celular).
   Elige la librería de componentes y justifícala en un ADR para la integradora (Angular Material con tema LUME o PrimeNG).
A2 Shell: sidebar oscuro, cabecera, migas de pan, menú por perfil y pantallas, e interceptor de token.
A3 Pantallas de ingreso, MFA, recuperación y cambio de clave contra el contrato de seguridad.
   Mientras no esté el servicio, usa un mock del contrato.
A4 Arnés e2e Playwright para Angular: adapta los e2e de ingreso y de caducidad del monolito.
Criterio: accesibilidad AA (contraste, foco, teclado), funciona a 360 px y los e2e pasan en chromium, firefox y webkit.
Deja documentado en frontend/README cómo una sesión de módulo agrega sus pantallas.
```

---

## OLEADA 2 · VISITAS A

```
Eres la SESIÓN VISITAS A. Worktree C:\Users\dgonzalez\dev-digsa-arqui-visitas-a, rama visitas-a, desfase +300.
Construyes servicio-visitas (backend compartido con VISITAS B: tú creas el servicio y su base) y sus pantallas Angular.
Origen: paquetes catalogos, maestros, agenda (y lo que use de plataforma); pantallas Catalogos, Maestros,
Homologacion, Agenda, Calendario, Solicitudes, Prorrogas, Propuesta, MisVisitas y DescargasDelDia; sus e2e.
Agentes: explorador-codigo, qa-tdd, revisor-java, revisor-bd-postgresql, revisor-angular, qa-e2e, qa.

Fases:
VA1 Servicio y esquema: migraciones Flyway propias (no copies los números del monolito), catálogos y maestros.
VA2 Agenda: solicitudes, prórrogas, propuesta y calendario, con sus reglas desde librerias/reglas.
VA3 Pantallas Angular y e2e adaptados.
Coordina con VISITAS B: tú eres dueña del esqueleto del servicio y del esquema; ella agrega paquetes y
migraciones en el rango que acuerden con la integradora.
```

## OLEADA 2 · VISITAS B

```
Eres la SESIÓN VISITAS B. Worktree C:\Users\dgonzalez\dev-digsa-arqui-visitas-b, rama visitas-b, desfase +400.
Empieza cuando VISITAS A tenga VA1 integrada en develop.
Origen: paquetes ejecucion, hallazgos, opl, tablero y alertas de visitas; pantallas VisitaDetalle, Checklist,
Hallazgos, Seguimiento, Opl, OplResponder, CarguesOpl, Riesgo, Mapas, Dashboard y Alertas; el acta (VIS-14); sus e2e.
Agentes: explorador-codigo, qa-tdd, revisor-java, revisor-bd-postgresql, revisor-angular, qa-e2e, qa.

Fases:
VB1 Ejecución de la visita, checklist y acta.
VB2 Hallazgos, seguimiento, OPL (cargues y respuestas).
VB3 Tablero, riesgo y mapas.
VB4 Pantallas Angular y e2e adaptados.
Las alertas que se notifican se publican como eventos para servicio-notificaciones; no envíes correos directamente.
```

## OLEADA 2 · REPORTES Y NOTIFICACIONES

```
Eres la SESIÓN DE REPORTES Y NOTIFICACIONES. Worktree C:\Users\dgonzalez\dev-digsa-arqui-reportes, rama reportes, desfase +500.
Origen: paquetes motorreportes, reportes, exportacion, correos y la bandeja de salida/despachador de plataforma;
pantallas Reportes, TiemposReportes, VerificarReporte, Exportacion, Correos, CorreosPlantillas y CorreosDestinatarios; sus e2e.
Agentes: explorador-codigo, qa-tdd, revisor-java, revisor-bd-postgresql, rendimiento, revisor-angular, qa-e2e, qa.

Fases:
R1 servicio-notificaciones: consume eventos, plantillas, destinatarios, envío por correo y SMS (solo alertas
   críticas), bitácora con registro Y contenido guardados 365 días, y Mailpit en local.
R2 servicio-reportes: motor de reportes, verificación de reportes firmados y exportación. Los datos de visitas
   y seguridad se obtienen por API o por eventos, nunca leyendo sus bases.
R3 Pantallas Angular y e2e adaptados.
Los reportes que dependen de presupuesto o analítica se quedan en el monolito: márcalos en docs y no los migres.
```
