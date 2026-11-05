# ADR-011 · Frontend Angular con el design system LUME

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
La arquitectura objetivo pide Angular (web y móvil). El monolito tiene React 18 + Mantine 7 con el design system LUME (`estilos_suite_digsa_LUME.css`, `tema_mantine_LUME.ts`, `guia_interfaz_suite_digsa.md`, `LUME_design_system.md`) y pruebas Playwright en `digsa-java/e2e/pruebas` (el prompt decía `frontend/e2e`; en el repo están en `e2e/` de la raíz).

## Decisión
- **Angular en la versión mayor con soporte LTS vigente** al momento de la fase I1, confirmada en el calendario oficial (angular.dev/reference/releases) y fijada exacta en `package.json` con `package-lock.json`. Node.js LTS fijado en `.nvmrc` y en el CI.
  - **Fijado en I1 (2026-11-05): Angular 21.2** (CLI 21.2.29, core 21.2.26), la línea en LTS hoy, con soporte hasta 2027-06-30; Node 24.19.0; pruebas unitarias con Vitest. Angular 22 está en soporte activo pero aún no es LTS: la subida a 22 se planifica antes del fin de soporte de 21.
- Componentes **standalone**, *signals* para el estado local, rutas con *lazy loading* por módulo funcional (`seguridad`, `visitas`, `reportes`, `notificaciones`) y *guards* según `pantallas` del usuario.
- Sin Mantine (es de React). El design system LUME se porta como **tokens CSS** (variables) y una capa de componentes propia en `frontend/src/app/ui/`, tomando como fuente `estilos_suite_digsa_LUME.css` y los valores de `tema_mantine_LUME.ts`. Solo se añade una librería de componentes (Angular CDK como base) si la guía lo justifica, y con aprobación de la integradora.
- Cliente HTTP **generado** desde `contratos/` (ADR-007). Sin tokens en el navegador: cookie del BFF + `X-XSRF-TOKEN` (ADR-003). El `HttpClient` trata 401, 419 y 403 igual que la SPA actual: 401 lleva al ingreso (por `/bff/oauth2/authorization/lume`, ADR-014) con el mensaje del motivo; 419 **no** recarga, muestra «La página expiró. Recárguela e intente de nuevo.»; 403 muestra «No tiene permiso para esta acción.» (corregido 2026-11-05 según el monolito).
- Textos en español, idénticos a los del monolito cuando la pantalla existe en él. Accesibilidad WCAG 2.1 AA (agente `accesibilidad`).
- Pruebas: unitarias con el *runner* por defecto de la versión elegida; **e2e Playwright** en `e2e/` del monorepo, portadas desde `digsa-java/e2e/pruebas` cambiando solo los selectores y conservando los mismos casos y aserciones de negocio. Un caso del monolito que ya no pase se reporta; no se borra.
- Móvil: diseño adaptable (*responsive*) en esta migración. Una app nativa o PWA queda fuera hasta que el usuario lo pida.

## Consecuencias
- (+) Paridad verificable: los mismos casos e2e cubren el monolito y el sistema nuevo.
- (−) Portar el design system es trabajo de la sesión angular-base antes de que lleguen las pantallas de la oleada 2.
- (−) Analítica y presupuesto siguen en React durante la convivencia (ADR-008): dos implementaciones del mismo design system.
