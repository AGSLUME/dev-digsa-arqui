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
| C7 | Texto visible de dos botones de fila de Usuarios (A5; **único cambio visible**) | `paginas/seguridad/usuarios.html` | «Restablecer contraseña» y «Cerrar sesión», con nombre «Restablecer la contraseña de X» y «Cerrar la sesión de X»: el texto visible no está dentro del nombre (falla 2.5.3, nivel A; el control por voz no los encuentra) | «Restablecer la contraseña» y «Cerrar la sesión»; los `aria-label` (y las e2e que los usan) no cambian |
| C8 | Acciones de fila mientras corre una | `usuarios.html`, `modales-usuarios.ts` (Nombres de perfiles) | `disabled` en el botón pulsado: el foco cae al body; «Desbloquear» y «Cerrar sesión» desaparecen con el foco encima | `aria-disabled` (se ve y se anuncia igual, Playwright lo ve deshabilitado) y la acción no corre; si el botón se va, el foco pasa al primer botón de la fila (2.4.3). Cada «Guardar» de Nombres de perfiles dice de qué perfil es («Guardar el nombre de Auditor», 2.4.6) |
| C9 | Formulario en línea y tabla de Roles adicionales | `paginas/seguridad/roles-adicionales.ts` | Al abrir «Nuevo rol» o «Editar» el foco se queda en el botón (el formulario sale debajo); al cerrar se pierde. La tabla va en un `ScrollArea` | Foco al primer campo al abrir y de vuelta al botón que lo abrió al cerrar (2.4.3). La tabla es `TablaAdaptable` (tarjetas en celular; parada de Tab solo si desborda) |
| C10 | Botón con petición en curso (`cargando`), en **todas** las pantallas | `ui/boton.ts` | `loading` de Mantine: el botón queda `disabled` y el foco cae al body (al buscar, descargar, enviar, guardar…) | `aria-disabled` y `aria-busy`, sin `disabled`: conserva el foco; el clic (también el implícito de Enter en el formulario) se cancela mientras carga, así que sigue sin haber doble envío (2.4.3) |
| C11 | Logs del sistema | `paginas/seguridad/logs.*` | «Buscar» y «Ver más» no avisan nada; al irse «Ver más» el foco cae al body; «Ver (N)» se llamaba «Ver los cambios del registro N» (falla 2.5.3) | Región viva con el resultado («N registros.», «N registros más; M en total.»); el foco pasa al primer registro nuevo; el nombre empieza por el texto visible: «Ver (N) cambios del registro M» (la e2e `logs-cambios` busca `/^Ver \(\d+\) cambios del registro/`). Las tablas llevan nombre aunque no desborden |

Textos visibles de C7 y C11 («Restablecer la contraseña», «Cerrar la sesión», «Ver (N) cambios del registro M»): **aprobado por el
usuario el 2026-11-06** (lo comunicó la integradora al aprobar A5).

Mejoras de A5 que quedan sin aplicar (bajas): el error del teléfono que sale al salir del campo no se anuncia (el lector lo oye al volver
al campo y en el resumen al guardar, 3.3.1 se cumple); las insignias de una celda no van en lista; en Logs, «—» como valor vacío, el
separador « · » del resumen de «Antes y después», un aviso de «filtros sin aplicar» (descargar y enviar usan lo aplicado con «Buscar»,
como el monolito) y el error de «Ver más» junto al botón (hoy sale arriba, con `role="alert"`).

Quedan como el monolito (mejoras sin bloqueo): grupos del menú lateral sin `role="group"`, «Acceso restringido.» sin h1, ícono de «Menú»
que no cambia a «×», ayudas emergentes no «hoverables» (repiten el nombre accesible; Escape las cierra en cualquier momento).

## Lint de accesibilidad (`@angular-eslint`, conjunto accessibility)

Primera corrida (2026-11-05, `ng lint` en el CI desde entonces): 3 hallazgos, ninguno choca con la paridad, los 3 corregidos:
- `lume-campo` aceptaba el id del control con el alias `id`: el mismo id quedaba en el elemento `lume-campo` y en su `<input>` (id
  repetido). Ahora es `idControl` (prueba en `campo.spec.ts`).
- El Escape del panel del menú estaba escuchado en `header` y `nav` (contenedores no enfocables): pasó a un escuchador del documento,
  activo solo con el panel abierto (prueba en `e2e/pruebas/teclado.spec.ts`).

Reglas desactivadas (en su línea, con el motivo en el código):
- `ui/selector.ts`, opciones del listbox: `click-events-have-key-events` e `interactive-supports-focus`. Falso positivo para el patrón
  combobox con `aria-activedescendant` (ARIA APG): las opciones no deben recibir foco; el teclado lo atiende el campo `role="combobox"`
  (↑↓, Inicio/Fin, Enter, Escape, letra) y el clic es solo para el puntero. Probado con teclado en `e2e/pruebas/componentes.spec.ts`.

## Pendiente de probar con NVDA (junto con D1–D3)

| # | Qué | Dónde | Comportamiento (= monolito) | Qué verificar |
|---|---|---|---|---|
| D4 | Confirmaciones con `window.confirm` nativo | Usuarios y Roles adicionales (A5): desbloquear, cerrar la sesión, quitar un rol… | Diálogo nativo del navegador, como en el monolito (las e2e lo aceptan con `page.once('dialog')`) | Que NVDA lea el texto y los botones en Chrome, Edge y Firefox; que el foco vuelva al botón. Si falla, alternativa: `lume-modal` con `role="alertdialog"`, cambiando las e2e a `getByRole('alertdialog')` |
| D5 | Selector (combobox) | `ui/selector.ts` | Nuevo en Angular (Mantine Select en el monolito) | Que NVDA anuncie la opción resaltada al moverse con ↑↓ (`aria-activedescendant`) y el número de resultados al filtrar (región `role="status"`) |

## Mejoras ya aplicadas (no son desviaciones)

Enlaces y botones azules dentro de alertas a --lume-blue (bloqueante AA corregido, con prueba en `tema.spec.ts`), reflow de botones e
insignias a 360 px, modo de alto contraste (`forced-colors`), tabla desplazable enfocable con teclado, error antes que la descripción en
`aria-describedby`, foco blanco sobre el sidebar oscuro y `scroll-padding-top` por la cabecera fija.
