# Pendientes de dependencias (riesgos aceptados con fecha)

Lo que `npm audit` / análisis de dependencias reporta y la integradora acepta temporalmente. Cada fila tiene fecha de revisión; al llegar, se intenta cerrar y se actualiza esta tabla.

| Fecha | Dónde | Hallazgo | Por qué se acepta | Revisar el | Plan |
|---|---|---|---|---|---|
| 2026-11-05 | `frontend` (solo desarrollo) | 31 moderadas: minimatch GHSA-g3hv-75wg-m249 (DoS por globparts, <11.0.1) y glob GHSA-3g3m-2fjr-6pw4 (<14.0.2), por `@angular/cli`, `eslint`, `typescript-eslint`, `angular-eslint` | Herramientas locales sobre patrones propios; nada llega al bundle (`npm audit --omit=dev` = 0). Las versiones corregidas son mayores y tienen menos de 2 semanas | 2026-11-11 | angular-base prueba `overrides` minimatch 11.0.x / glob 14.0.x con build, test, lint, `npm run api` y e2e completas; si rompen, se espera a la corrección dentro de la línea 21 de Angular |

## Cerrados

| Fecha | Dónde | Hallazgo | Cómo |
|---|---|---|---|
| 2026-11-05 | `frontend` (desarrollo) | basic-ftp GHSA-c475-qrg2-pj4r (alta), por `@openapitools/openapi-generator-cli` | `overrides` basic-ftp 6.2.3 (5bae77d); generador verificado |
