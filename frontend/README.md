# frontend · LUME en Angular

Angular 21 (línea LTS, soporte hasta 2027-06-30), componentes standalone, sin zone.js, pruebas con Vitest (ADR-011).
Versiones exactas en `package.json`; Node según `../.nvmrc`.

```bash
npm ci                                   # instala exactamente lo del lockfile
npx ng serve --port $ANGULAR_PUERTO      # el puerto de la sesión (infra/.env); se entra por el gateway
npx ng build
npx ng test --watch=false
```

- Se usa a través del gateway (`http://localhost:$GATEWAY_PUERTO`), que enruta `/**` a este servidor y `/api/**` a los servicios. Sin tokens en el navegador: la cookie del BFF y `X-XSRF-TOKEN` (ADR-003).
- npm 11 deja sin aprobar los scripts de instalación de `@parcel/watcher`, `esbuild`, `lmdb` y `msgpackr-extract`. El build y las pruebas no los necesitan; no se aprueban sin revisión de la integradora.
- Pendiente de la sesión angular-base: design system LUME como tokens CSS, shell, cliente generado desde `contratos/` y e2e Playwright en `../e2e/`.
