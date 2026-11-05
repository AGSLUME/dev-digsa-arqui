# frontend · LUME en Angular

Angular 21 (línea LTS, soporte hasta 2027-06-30), componentes standalone, sin zone.js, *signals*, pruebas con Vitest (ADR-011). Solo
Angular CDK como librería de componentes, con los componentes LUME propios (ADR-013). Versiones exactas en `package.json`; Node según
`../.nvmrc`.

```bash
npm ci                                   # instala exactamente lo del lockfile
npm run api                              # genera el cliente desde ../contratos (Java 21; también lo hacen start, build y test)
node mock/servidor.mjs                   # mock del gateway + seguridad en 127.0.0.1:9200, mientras no exista el gateway real
npm start -- --port 4220                 # ng serve; /api, /bff y /oauth2 van al gateway (o al mock) por proxy.conf.mjs
npx ng build
npx ng test --watch=false
```

- Puertos de la sesión angular (ADR-010): Angular 4220, gateway 9200. Otro gateway: `DIGSA_GATEWAY_PUERTO`.
- El mock (`mock/servidor.mjs`) sigue `contratos/seguridad.yaml` y el comportamiento del monolito, con datos **ficticios**
  (`mock/datos.mjs`, dominio `@angular.prueba.local`). Manda los correos (código MFA, enlaces) al Mailpit ya instalado
  (127.0.0.1:1025; se leen en <http://127.0.0.1:8025>). Usuarios: `admin.angular` (Administrador), `coordinador.angular`,
  `auditor.angular`, `inicial.angular` (contraseña inicial que se cambia al entrar) y `definir.angular` (sin contraseña: recibe enlace).
  Las claves de prueba están en `mock/datos.mjs`. Captcha: la clave de **prueba** pública de Cloudflare Turnstile (siempre aprueba);
  `DIGSA_MOCK_TURNSTILE=no` lo desactiva.
- Sin tokens en el navegador: la cookie del BFF y `X-XSRF-TOKEN` (ADR-003). El ingreso sigue ADR-014: sin sesión, página completa a
  `/bff/oauth2/authorization/lume`; el emisor manda a `/ingreso`; al terminar, `window.location.assign(continuar)`.
- npm 11 deja sin aprobar los scripts de instalación de `@parcel/watcher`, `esbuild`, `lmdb` y `msgpackr-extract`. El build y las
  pruebas no los necesitan; no se aprueban sin revisión de la integradora.
- Cliente HTTP (ADR-007): `scripts/generar-api.mjs` corre `typescript-angular` de openapi-generator **7.26.0** (el mismo del pom)
  sobre `contratos/<servicio>.yaml` hacia `src/app/api/<servicio>/` (no se versiona). Antes verifica el SHA-1 del jar contra el que
  publica Maven Central; para comprobarlo a mano:
  `curl -s https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar.sha1` (debe
  dar `b96b148fcf482808037f68fbad99cb4d7e65d40b`) y `sha1sum node_modules/@openapitools/openapi-generator-cli/versions/7.26.0.jar`.
  Un contrato nuevo se agrega en `SERVICIOS` del script y en `src/app/nucleo/proveedores.ts`.
- Licencias de lo que se copia de terceros (trazos de Tabler Icons, fuente Inter): `LICENCIAS-TERCEROS.md`.
- E2E: `../e2e` (Playwright en Chromium, Firefox y WebKit; ver su README).

## Estructura

| Carpeta | Qué hay |
|---|---|
| `src/styles/` | Tokens `--lume-*` (`lume.css`, única fuente de color), complementos, componentes y shell |
| `src/app/ui/` | Design system LUME: `lumeBoton`, `lumeConMotivo`, `lume-campo` + `lumeEntrada`, validación, `lume-insignia`, `lume-alerta`, `lume-modal`, `lumeTablaAdaptable`, `lume-encabezado-pagina`, `lume-icono`, `lumeAyuda`, avisos, sonido. Importar de `ui/index.ts` |
| `src/app/nucleo/` | Lógica sin pantalla: sesión, interceptor HTTP, guardas, formato de cifras y fechas, inactividad, menú, migas, validaciones |
| `src/app/shell/` | La plantilla: sidebar, cabecera con migas, aviso de inactividad |
| `src/app/paginas/` | Pantallas: públicas (ingreso, claves), inicio, cuenta, estados; y una carpeta por módulo |
| `src/app/rutas-modulos.ts` | **Las rutas de las pantallas de los módulos** (lo que edita cada sesión de módulo) |
| `mock/` | Mock local del gateway + seguridad (solo desarrollo y e2e) |

## Agregar pantallas (sesiones de módulo)

El servidor decide **qué** pantallas ve cada perfil (`menu` y `pantallas` de `GET /api/sesion`, que salen de `reglas/Pantallas`); el
frontend decide **dónde y cómo** se muestran. Para portar una pantalla del monolito:

1. **Carpeta del módulo.** `src/app/paginas/<modulo>/<pantalla>.ts` (por ejemplo `paginas/visitas/agenda.ts`), componente standalone
   con `ChangeDetectionStrategy.OnPush` y *signals*. Textos y `data-testid` **idénticos** a los del monolito (las e2e se portan
   cambiando solo selectores).
2. **Ruta.** En `src/app/rutas-modulos.ts`, en el bloque de su módulo, una entrada por pantalla con la guarda `pantalla('<ruta>')` (la
   misma `ruta` de `reglas/Pantallas`) y carga perezosa:

   ```ts
   { path: 'agenda', canMatch: [pantalla('agenda')], loadComponent: () => import('./paginas/visitas/agenda').then((m) => m.Agenda) },
   { path: 'agenda/visita/:id', canMatch: [pantalla('agenda')], loadComponent: () => import('./paginas/visitas/visita-detalle').then((m) => m.VisitaDetalle) },
   ```

   Sin permiso, la ruta no coincide y el shell muestra «Acceso restringido.» (como `Protegida`). Mientras una pantalla del menú no esté
   portada, su dirección muestra su título y «Esta pantalla todavía no está disponible en la versión nueva.». Los parámetros de la ruta y
   de la consulta llegan como `input()` del componente (`withComponentInputBinding`).
3. **Menú.** Una pantalla nueva aparece sola en el menú, en el módulo de su grupo del servidor. Para darle módulo, posición, ícono y
   numeral del Anexo 5 se agrega en `PRESENTACION` de `src/app/nucleo/menu.ts` (el orden de ese objeto es el orden del menú). Íconos:
   los de `src/app/ui/trazos-iconos.ts`; uno nuevo se copia de Tabler Icons (MIT) con sus trazos `d`.
4. **La página.** Empieza con `<lume-encabezado-pagina titulo="…" fuente="Anexo 5 · 4.5">` (el único h1; pone el título de la
   pestaña). Secciones en `h2`/`h3`, sin saltos. Cifras y fechas solo con `nucleo/formato.ts` (`cifra`, `porcentaje`, `fecha`,
   `fechaHora`…); nunca `toLocaleString`, `Intl.NumberFormat` ni los pipes `number`/`date` (lo vigila `formato.spec.ts`).
5. **Formularios.** `crearValidacion({ etiquetas, campos, testid })` + `<form [lumeValidacion]="v" novalidate>` +
   `<lume-resumen-errores [validacion]="v" />` + `<lume-campo etiqueta="…" [error]="v.error('campo')"><input lumeEntrada …></lume-campo>`;
   en el error del servidor, `v.errorServidor(e, 'No se pudo …')`. Un botón que todavía no se puede usar: `lumeConMotivo` (nunca
   `disabled` sin explicar). Mientras se envía: `[cargando]`. Éxitos: `inject(Avisos).mostrar(mensaje)`.
6. **Tablas y modales.** `<div lumeTablaAdaptable="Título"><table class="lume-tabla">…</table></div>` (tarjetas en celular).
   `<lume-modal [abierto]="…" titulo="…" (cerrar)="…"><ng-template lumeCuerpoModal>…</ng-template></lume-modal>` (pantalla completa en
   celular; el cuerpo se crea al abrir).
7. **HTTP.** `HttpClient` con rutas relativas (`/api/<servicio>/…`): el interceptor pone el CSRF, cuenta la actividad, convierte los
   errores en `ErrorApi` (con `mensaje`, `motivo`, `campo`) y lleva al ingreso cuando la sesión se cerró. Los tipos salen del cliente
   generado desde `contratos/<servicio>.yaml` (ADR-007) en `src/app/api/` (no se escribe a mano ni se versiona).
8. **Pruebas.** Unitarias junto al archivo (`*.spec.ts`, Vitest); las del monolito se portan con los mismos casos. E2E en
   `../e2e/pruebas/<modulo>.spec.ts`, portadas de `digsa-java/e2e/pruebas` cambiando solo selectores; `responsivo-360.spec.ts` recorre
   sola las pantallas nuevas del menú a 360 px.
9. **Accesibilidad AA.** Íconos `aria-hidden`; botones de solo ícono con `aria-label`; foco visible; 44 px en lo táctil; nada de
   desplazamiento horizontal a 360 px. Lo que choque con la paridad del monolito se anota en `docs/sesiones/DESVIACIONES_A11Y.md`.
