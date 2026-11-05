# Desviaciones de accesibilidad pendientes de decisión

Hallazgos de accesibilidad del frontend Angular que **se dejaron como el monolito** (paridad, ADR-011) hasta probarlos con un lector de
pantalla (NVDA en Windows; VoiceOver en Safari si se puede). Decisión de la integradora (2026-11-05): no se cambian antes de esa prueba.
Origen: auditoría del agente `accesibilidad` sobre la fase A1 (componentes en `frontend/src/app/ui`). Los hallazgos de ESLint
(`@angular-eslint`, conjunto accessibility) que choquen con la paridad también se anotan aquí, en vez de cambiar el comportamiento.

Cómo probar: `npm start` en `frontend` con el mock (`node mock/servidor.mjs`), NVDA con Firefox y con Chrome, y anotar lo que dice el
lector en cada paso.

| # | Qué | Dónde | Comportamiento actual (= monolito) | Riesgo | Propuesta si la prueba lo confirma |
|---|---|---|---|---|---|
| D1 | Resumen de errores: anuncio doble | `ui/validacion.ts` (`avisar`, `ResumenErrores`) y `ui/insignia.ts` (`lume-alerta`) | El resumen es `lume-alerta` con `role="alert"`; se inserta (el lector lo anuncia como región viva) y además recibe el foco cuando el error es del servidor, o el foco va al primer campo con error cuando es de la validación | Con error del servidor el lector puede leer el resumen dos veces (al insertarlo y al enfocarlo); con error de campo, el foco al campo puede cortar el anuncio del resumen | Error del servidor: foco al resumen **sin** `role="alert"` (`rol="none"` + `aria-labelledby` al título). Error de campo: conservar `role="alert"` y el foco al campo. `lume-alerta` ya acepta `rol` (`alert`, `status`, `none`) |
| D2 | Alertas informativas con `role="alert"` | `ui/insignia.ts` (`lume-alerta`, rol por omisión) | Toda `lume-alerta` es `role="alert"`, también las estáticas: `motivo-cierre` del ingreso, el aviso azul de `definir_clave` o `cambiar_clave` | Al cargar la página el lector las anuncia de forma asertiva y compiten con el resto | `rol="status"` (o ninguno) para avisos estáticos e informativos; `alert` solo para errores. Ojo: las e2e del monolito no buscan esas alertas por rol, así que el cambio no rompe selectores |
| D3 | Botón primario bloqueado con motivo | `styles/componentes.css` (`.lume-boton--primario:is(:disabled, [data-disabled])`) y `ui/boton.ts` (`lumeConMotivo`) | Texto blanco sobre #93C5FD (1,80:1), igual para `disabled` nativo y para el bloqueado con motivo (`aria-disabled`) | WCAG 1.4.3 exime a los componentes inactivos, pero el botón con motivo sigue enfocable y activable (explica qué falta), y un evaluador estricto podría no aplicar la excepción | Para `[data-disabled]` (con motivo) usar el aspecto apagado de las demás variantes: fondo `--lume-bg`, texto `--lume-neutral-text`, borde `--lume-border` (7:1). Dejar #93C5FD solo para `:disabled`. Es un cambio visual respecto al monolito: lo aprueba diseño |

## Verificación pendiente de D3

La integradora pidió confirmar con la prueba que el botón inactivo está exento por 1.4.3 («Text or images of text that are part of an
inactive user interface component … have no contrast requirement»). La duda es si un botón con `aria-disabled="true"` que sigue
recibiendo el foco y responde al clic (con el sonido y el foco al motivo) cuenta como «inactivo». Se anota el resultado de la prueba con
NVDA (qué anuncia: «no disponible» o «atenuado») y la decisión.

## Cambios aplicados que se apartan del monolito (A2–A3, sin cambio visual)

Bloqueantes AA o fallos de teclado de la auditoría de A2–A3 que el monolito también tiene; se corrigieron porque no cambian lo que se ve
ni los textos ni los `data-testid`:

| # | Qué | Dónde | Monolito | Ahora |
|---|---|---|---|---|
| C1 | Panel del menú en celular | `shell/plantilla.*` | Al abrir, el foco se queda en «Menú»; Escape no cierra; con el panel abierto el Tab cae en controles tapados | Al abrir, foco a la primera entrada; Escape cierra y vuelve a «Menú»; lo de atrás queda `inert` (2.4.11, 2.4.3) |
| C2 | Teclado dentro del aviso de inactividad | `shell/aviso-inactividad.ts` | Cualquier tecla renueva la sesión y cierra el aviso: con el teclado no se llega a «Salir» | Las teclas dentro del aviso no cuentan como actividad; «Seguir conectado» y «Salir» se eligen con el teclado |
| C3 | Cambio de pantalla | `shell/plantilla.ts` | El foco se queda donde estaba (en celular, en el body) | El foco va al contenido (`main`), y el lector empieza por el h1 nuevo |
| C4 | Pasos del ingreso | `paginas/publicas/ingreso.ts` | Al pasar al código, el foco cae en el body | El foco va a la primera casilla (o al primer campo del cambio de clave) |
| C5 | Título de la pestaña en las públicas | ingreso, claves, sin conexión | «Suite DIGSA» | «Suite DIGSA · Ingreso», «· Recuperar contraseña», «· Definir contraseña», «· Sin conexión» (2.4.2) |
| C6 | Avisos de éxito | `ui/avisos.ts`, `styles/shell.css` | La región aparece con el texto | La región existe siempre y el aviso de «Mi contraseña» sale después de cerrar el modal (4.1.3) |

Quedan como el monolito (mejoras sin bloqueo): grupos del menú lateral sin `role="group"`, «Acceso restringido.» sin h1, ícono de «Menú»
que no cambia a «×», ayudas emergentes no «hoverables» (repiten el nombre accesible; Escape las cierra en cualquier momento).

## Mejoras ya aplicadas (no son desviaciones)

Enlaces y botones azules dentro de alertas a --lume-blue (bloqueante AA corregido, con prueba en `tema.spec.ts`), reflow de botones e
insignias a 360 px, modo de alto contraste (`forced-colors`), tabla desplazable enfocable con teclado, error antes que la descripción en
`aria-describedby`, foco blanco sobre el sidebar oscuro y `scroll-padding-top` por la cabecera fija.
