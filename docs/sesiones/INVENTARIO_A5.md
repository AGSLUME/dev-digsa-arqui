# Inventario para A5 · Usuarios, Roles adicionales y Logs (monolito → Angular)

Preparado por la sesión angular-base el 2026-11-05, antes de tener el contrato `/api/usuarios/**` (seguridad 0.4.0), para arrancar A5
en cuanto llegue. Fuente: lectura del monolito `digsa-java` (develop 1ff82de) por el agente `explorador-codigo`; el detalle literal
(textos, `data-testid`, llamadas, casos e2e) está a partir de «Inventario literal». Los datos que aparecen son los ficticios de las
pruebas del monolito.

## Cómo se usa en A5

1. **Componentes que faltan en el design system** (sección E), en este orden, cada uno con pruebas y revisión de accesibilidad antes de
   las pantallas: selector con búsqueda (sobre `Overlay`/`Listbox` del CDK, con el patrón que usan las e2e: `textbox` con nombre y
   `option` en un portal), casillas y grupo de casillas con descripción y error, interruptor, campo numérico, campo de fecha
   `DD/MM/AAAA`, paginación numérica con «Mostrando X–Y de Z …» (25 por página) y aviso que no se cierra solo (naranja).
   `window.confirm` se conserva nativo por paridad (las e2e lo aceptan con `page.once('dialog')`).
2. **Pantallas** en `src/app/paginas/seguridad/` (`usuarios.ts` con los modales de usuario, contraseña inicial, Seguridad, nombres de
   perfiles y Roles adicionales; `logs.ts`), rutas `usuarios` y `logs` en `rutas-modulos.ts`. Textos y `data-testid` de las secciones
   A, B y C.
3. **Cliente** generado del contrato 0.4.0 (ADR-007); el mock se amplía con esas rutas **después** de que el contrato entre a develop.
4. **E2E**: `usuarios-y-logs.spec`, `roles.spec`, `logs-cambios.spec`, y se quitan los `test.fixme` de `sesion.spec` y
   `cuenta-y-seguridad.spec` (sección D).

## Diferencias ya conocidas con el sistema nuevo

- Punto F.8: en el monolito el enlace para definir la contraseña sale en el aviso («(local: http…)») y las e2e lo extraen de ahí. En el
  sistema nuevo va solo por correo (contrato de seguridad): las e2e lo leerán de Mailpit, filtrando por el destinatario
  (`@angular.prueba.local`), como ya hace el código MFA.
- Correos de las e2e del monolito (`@example.com`): se cambian a `@angular.prueba.local` (ADR-010).
- Los casos que dependen de datos sembrados del monolito (`admin.e2e`, módulos de `logs/opciones`) se sembrarán por el contrato o por
  el control del mock.

---

# Inventario literal: Usuarios, Roles adicionales y Logs (monolito digsa-java)

Solo lectura. Rutas base, salvo indicación:
- FE = `C:\Users\dgonzalez\dev\digsa-java\frontend\src`
- E2E = `C:\Users\dgonzalez\dev\digsa-java\e2e\pruebas`
- BE = `C:\Users\dgonzalez\dev\digsa-java\backend\app\src\main\java\co\com\agsamericas\digsa`

Fuentes leídas: `FE/paginas/Usuarios.tsx` (U), `RolesAdicionales.tsx` (R), `Logs.tsx` (L), `FE/shared/*` (paginacion, validacion, validaciones, formato, EncabezadoPagina, TablaAdaptable, api, alertas), los specs, `apoyo.ts` y los controladores `ControladorUsuarios`, `ControladorLogs`.

Notas generales:
- Roles Adicionales NO es una página: es un `<Modal>` abierto desde Usuarios y montado dentro de U (U:769).
- Hay tres rutas protegidas con `<Protegida ruta="...">` en `FE/App.tsx:136,138`: `/usuarios` y `/logs`. Roles no tiene ruta.
- En el menú, `usuarios` y `logs` están en el grupo «Seguridad», numeral '3' (`FE/shared/menu.ts:73-74`).
- Los enlaces del menú se llaman «Usuarios» y «Logs del sistema» (los e2e los buscan con `getByRole('link', {name})`).

---------------------------------------------------------------------
## 0. Utilidades compartidas que usan

### EncabezadoPagina (`FE/shared/EncabezadoPagina.tsx:13-69`)
- Pinta un `h1` con el título.
- Pinta una descripción opcional (no la usan las 3 pantallas).
- A la derecha pinta la fuente en un `<span class="lume-fuente">` con `<VisuallyHidden>Fuente normativa: </VisuallyHidden>` delante, y luego las acciones.
- Pone `document.title = "Suite DIGSA · <titulo>"` (líneas 4-7, 33-39).

### Paginación del navegador (`FE/shared/paginacion.tsx`)
- `POR_PAGINA = 25` (línea 6).
- Texto del total, con `data-testid="total-<nombre>"` (línea 68):
  - sin filas: `0 usuarios`;
  - con filas: `Mostrando X–Y de Z usuarios`, con guion «–» y `conteo()`.
- Los botones de página solo salen si `paginas > 1` (línea 71).
- Mantine `Pagination` con `size="sm"`, `siblings={1}` y `aria-label="Páginas de usuarios"`.
- `aria-label` de los controles (línea 10): «Primera página», «Página anterior», «Página siguiente», «Última página».
- `usePaginacion(lista)` ajusta la página si la lista se achica.
- `useIrAFila`: tras crear o editar, lleva a la página del usuario.
- Si la búsqueda oculta al usuario recién guardado, la quita (U:127-132).

### Validación con resumen (`FE/shared/validacion.tsx`)
Es el equivalente a «validación con resumen» del Angular. Reglas relevantes:
- Resumen: `Alert color="red" role alert` con `data-testid` configurable (línea 164).
- Título del resumen:
  - 1 campo con error: `Revise el campo marcado: <etiqueta>`;
  - varios: `Revise los N campos marcados`, con lista `List` de etiquetas cuando N>1;
  - error de servidor sin campo: `No se pudo completar la acción`;
  - error de servidor con `campo` conocido: `Revise el campo marcado: <etiqueta>`.
- Cada mensaje de campo va junto al campo (prop `error`); el resumen solo nombra los campos.
- El error del servidor va en el cuerpo del resumen (`general` = `ErrorApi.message`, o el texto por defecto si no es `ErrorApi`).
- El campo que el servidor marca con `campo` en el 422 queda marcado (solo `true`, sin texto).
- En Checkbox.Group y Switch ese `true` se vuelve el texto `Revise este campo: el motivo está en el aviso de arriba.` (`conTexto`, `validaciones.ts:138`).
- Al fallar la validación local: suena `sonarError()` y el foco va al primer campo con error. Si el error es del servidor, el foco va al resumen.

### Validaciones locales (`FE/shared/validaciones.ts`)
- `errorCorreo(correo, invalido, vacio)` (línea 17): vacío devuelve el mensaje `vacio`; si no, comprueba `correoValido`.
- Patrón de correo: `/^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/` y máximo 150 caracteres.
- `errorClave(clave)` (línea 112), en este orden:
  1. si hay ñ/Ñ o tildes (`[ñÑáéíóúÁÉÍÓÚüÜ]`): `La contraseña no puede tener ñ ni tildes.`
  2. si tiene menos de 12 caracteres: `La contraseña debe tener mínimo 12 caracteres.`
- `errorTelefono(escrito, guardado)` (`FE/shared/alertas.ts:123`): vacío o igual al guardado es válido. Si no es un móvil colombiano normalizable, devuelve `ERROR_TELEFONO` (`alertas.ts:117`):
  `El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000).`
- `normalizarTelefono` está en `alertas.ts` (no leí su cuerpo; es la misma regla del backend `reglas/Alertas.normalizarTelefono`).

### Formato (`FE/shared/formato.ts`)
- `fechaHora(v, {segundos})` (línea 107):
  - Un instante con zona (`Z` o `±hh:mm`) o un número se convierte a hora de Bogotá, formato `dd/mm/aaaa HH:mm` (con `:ss` si `segundos`).
  - Un texto `aaaa-mm-dd[ T]HH:mm[:ss]` sin zona ya viene en Bogotá y solo se reordena.
  - Vacío devuelve `—`.
- `conteo(n)`: entero sin separador de miles; si no es un número devuelve `—`.
- `hoyBogota()` devuelve `aaaa-mm-dd`; `sumarDias(iso, n)` también (líneas 83, 89).
- Es requisito del proyecto (Anexo 5 2.4): nada de separador de miles, hora militar de Bogotá, fechas dd/mm/aaaa.

### TablaAdaptable (`FE/shared/TablaAdaptable.tsx`)
- Envuelve una `Table` de Mantine en `ScrollArea type="auto"`, clase `tabla-adaptable`.
- Pone `role` table/row/columnheader/cell/rowheader y `data-etiqueta` en cada celda, a partir del `th` de la última fila del encabezado.
- En celular y tableta vertical se apila como tarjetas por CSS.
- Una celda con `colSpan` igual a todas las columnas lleva etiqueta vacía.
- Los `data-testid` de `Table` y `Tr` se conservan.

### API (`FE/shared/api.ts`)
- `ErrorApi(estado, mensaje, datos)`.
- `mensaje` sale de `datos.mensaje`; si no, `Error <status>`. Sin conexión: `No hay conexión con el servidor. Revise su conexión e intente de nuevo.`
- Los POST/PUT envían el token `X-XSRF-TOKEN`.
- `descargar(ruta)` crea un blob y un `<a download>`. El nombre sale del `Content-Disposition` (`filename*` o `filename="..."`).

---------------------------------------------------------------------
## A. USUARIOS (`FE/paginas/Usuarios.tsx`)

### A.1 Estructura

**Encabezado** (U:247-285)
- `titulo="Usuarios"` (h1).
- `fuente="Anexo 5 · 1.10–1.12, 3.1–3.7"`. Sin descripción.
- Acciones, en este orden, todas `Button`:
  1. «Seguridad» (variant default; abre el modal Seguridad, reinicia `minutos` y su validación).
  2. «Nombres de perfiles» (default).
  3. «Roles adicionales» (default; abre el modal de R).
  4. «Nuevo usuario» (primario).

No hay pestañas. Hay 4 modales: formulario de usuario, Asignar contraseña inicial, Seguridad y Nombres de perfiles, más el modal de R (sección B).

**Avisos fijos sobre la tabla**
- `datos.error` produce `<Alert color="red">{mensaje}</Alert>` (U:286).
- `{vAccion.resumen}` es el resumen de errores de las acciones de la tabla, con `data-testid="error-accion-usuario"` (U:100, 287). Título por defecto `No se pudo completar la acción`; texto del servidor en el cuerpo; texto por defecto `No se pudo completar la acción.` (U:178).

**Filtro** (U:288-297)
- `TextInput` con label «Buscar», placeholder «Nombre, usuario o correo», `maw=420`.
- Filtra en el navegador, sin tildes ni mayúsculas (`toLocaleLowerCase('es')`), por `includes` sobre nombre, usuario y correo (U:121-125).
- Al escribir, la página vuelve a 1 (U:295).
- No hay más filtros.

**Tabla** (U:298-444)
- `TablaAdaptable` con `Table striped highlightOnHover miw=760`.
- Cada fila: `<tr data-testid="usuario-<u.usuario>">` (U:313).
- 25 filas por página (`Paginacion nombre="usuarios"`, U:445).

Columnas, en orden:

| # | Columna | Celda |
|---|---|---|
| 1 | Nombre | `u.nombre` |
| 2 | Usuario | `u.usuario` |
| 3 | Correo | `u.correo` |
| 4 | Perfiles | un `Badge variant="light"` por cada perfil, con `nombrePerfil(id)` (nombre del catálogo, o el id si no está) |
| 5 | Último ingreso | `fechaHora(u.ultimoIngreso)` (sin segundos); `—` si es nulo. El servidor manda `yyyy-MM-dd HH:mm` ya en Bogotá |
| 6 | Estado | ver abajo |
| 7 | Acciones | ver abajo |

Celda Estado, `Group gap=4` con hasta 3 insignias:
- Insignia principal:
  - inactivo: color gray, texto «Inactivo»;
  - activo y bloqueado: color orange, «Bloqueado»;
  - activo y no bloqueado: color green, «Activo».
  - Es `Badge` por defecto (filled).
- Si `sesionAbierta`: `Badge color="blue" variant="outline"` con «Sesión abierta».
- Si `claveInicial`: `Badge color="grape" variant="outline"` con «Contraseña inicial».

**Acciones por fila** (`Group gap=4 wrap=nowrap`; todos los botones `size="xs" variant="light"`). El texto visible y el `aria-label` difieren, y los e2e buscan por el `aria-label`:

| Botón | Texto | aria-label | color | Condición | Efecto |
|---|---|---|---|---|---|
| 1 | Editar | (ninguno) | (por defecto) | siempre | abre el formulario con los datos del usuario (U:346-370) |
| 2 | `Inactivar` si activo, `Activar` si inactivo | `Inactivar a <nombre>` / `Activar a <nombre>` | gray si activo, green si inactivo | siempre; deshabilitado mientras `accion.isPending` | `window.confirm` y luego `PUT /api/usuarios/{id}/estado` con `{activo: !u.activo}` |
| 3 | Restablecer contraseña | `Restablecer la contraseña de <nombre>` | orange | siempre; deshabilitado con `accion.isPending` | `window.confirm` y luego `POST /api/usuarios/{id}/restablecer` |
| 4 | Contraseña inicial | `Asignar contraseña inicial a <nombre>` | grape | siempre; deshabilitado con `accion.isPending` | abre el modal «Asignar contraseña inicial» |
| 5 | Desbloquear | `Desbloquear a <nombre>` | orange | SOLO si `u.bloqueado`; deshabilitado con `accion.isPending` | `POST /api/usuarios/{id}/desbloquear`, SIN confirm |
| 6 | Cerrar sesión | `Cerrar la sesión de <nombre>` | red | SOLO si `u.sesionAbierta`; deshabilitado con `accion.isPending` | `window.confirm` y luego `POST /api/usuarios/{id}/cerrar-sesion` |

Textos de `window.confirm` (U:378, 391, 431):
- `¿Inactivar a <nombre>?` / `¿Activar a <nombre>?`
- `¿Enviar a <nombre> un enlace para definir una contraseña nueva?`
- `¿Cerrar la sesión abierta de <nombre>?`

El `accionTabla` (U:177-178) al éxito hace `vAccion.reiniciar()`. Al error hace `vAccion.errorServidor(e, 'No se pudo completar la acción.')`.

**Avisos de éxito** (`notifications.show`, color green, mensaje del servidor):
- Estado: `Usuario activado.` / `Usuario inactivado.` (BE `identidad/ControladorUsuarios.java:107`).
- Restablecer: `Se envió al correo del usuario el enlace para definir una contraseña nueva.` (línea 115).
- Desbloquear: `Usuario desbloqueado.` o `El usuario no estaba bloqueado.` (línea 140).
- Cerrar sesión: `Sesión cerrada.` o `El usuario no tenía una sesión abierta.` (línea 133).
- Clave inicial: `Contraseña inicial asignada. Entréguela al usuario por un canal seguro: deberá cambiarla al ingresar.` (línea 126).
- Seguridad: `Tiempo de inactividad actualizado: N minutos. Aplica desde la siguiente acción de cada usuario.` (línea 154).
- Perfil renombrado: `Perfil actualizado.` (línea 189).
- Guardar usuario (U:155-160):
  - mensaje = `r.mensaje`, más ` (local: <enlaceLocal>)` si viene `enlaceLocal`.
  - Si `r.correoOk === false`: color orange y `autoClose: false`.
  - Si no: color green y `autoClose: 6000`.
  - Textos de `Guardado.mensaje` (BE `identidad/ServicioUsuarios.java:136,144,145,165`):
    - con clave inicial: `Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: ...` (línea 136, texto cortado en mi lectura);
    - sin clave inicial: `Usuario creado. Se le envió el enlace para definir su contraseña.`;
    - correo fallido: `Usuario creado, pero NO se pudo enviar el correo (<error>). Use «Restablecer contraseña» cuando el correo funcione.`;
    - edición: `Usuario actualizado.`
  - El e2e lee el enlace con `/local: (http\S+)\)/` sobre el texto del aviso.

### A.2 Modal «Nuevo usuario» / «Editar usuario» (U:447-628)
- `Modal size="lg"`; el título es `Editar usuario` si `form.id`, si no `Nuevo usuario`.
- `<form noValidate>`. Dentro:
  - `{v.resumen}` con `data-testid="error-usuario"` y las etiquetas de `ETIQUETAS_USUARIO` (U:73-84).
  - En `onPointerDownCapture` se guarda el estado del puntero (para el blur del teléfono, ver abajo).

Etiquetas del resumen (U:73-84):

| Campo | Etiqueta |
|---|---|
| nombre | Nombre completo |
| usuario | Usuario |
| correo | Correo |
| documento | Documento |
| telefono | Teléfono móvil |
| perfiles | Perfiles |
| alcancePunto | Establecimiento (ESM) |
| alcanceRegional | Regional (OPL regional) |
| alcanceFuerza | Fuerza (Sanidad) |
| claveInicial | Contraseña inicial |

Campos, en orden:
1. **Nombre completo**: `TextInput`, required.
   - Valida `Escriba el nombre completo.` si está vacío tras `trim`.
   - El servidor exige mínimo 3 caracteres.
2. **Usuario**: `TextInput`, required.
   - Description: `Minúsculas sin tildes, números, punto, guion o guion bajo (3 a 50).`
   - El `onChange` pasa a minúsculas (`toLowerCase()`).
   - Valida: vacío da `Escriba el usuario.`; si no cumple `/^[a-z0-9._-]{3,50}$/` da `El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.`
3. **Correo**: `TextInput type="email"`, required.
   - Vacío: `Escriba el correo.`
   - Inválido: `Escriba un correo válido.`
4. Fila de 2 columnas (`SimpleGrid cols base 1, sm 2`):
   - **Documento**: `TextInput`, sin required y sin maxLength. Sin validación local (el servidor admite máximo 30 caracteres y es obligatorio para el perfil Auditor).
   - **Teléfono móvil**: `TextInput`, `type="tel"`, `inputMode="tel"`, `autoComplete="off"`, placeholder `+57 3xx xxx xxxx`, `data-testid="campo-telefono"`.
     - Description: `Móvil colombiano (+57 y 10 dígitos que empiezan por 3). Recibe por SMS las alertas críticas si su perfil es destinatario.`
     - Error del campo: `errorTelefono` (solo si `telefonoTocado`, es decir tras perder el foco), o la marca del servidor (`v.error('telefono')`) (U:241).
     - El `onBlur` marca «tocado». Si hay un puntero presionado dentro del formulario, difiere con `pointerup` más `setTimeout(...,0)`, para no mover el layout antes del clic (U:516-521).
     - El teléfono NO detiene el envío (el comentario de U:213 lo dice). El servidor responde 422 con `campo: "telefono"`.
     - Al editar, el teléfono guardado se conserva aunque no sea móvil (`telefonoGuardado`).
5. **Perfiles**: `Checkbox.Group label="Perfiles"` (U:524).
   - Un `Checkbox` por cada perfil de `datos.perfiles`, apilados (`Stack gap=6 mt=6`).
   - `value=String(p.id)`, `description={p.descripcion}`.
   - Label: el nombre si es base (`baseId === null`); si es rol adicional: `<nombre> (rol adicional · base <nombrePerfilBase>)`.
   - Error: `conTexto(v.error('perfiles'))`. Servidor: `Marque al menos un perfil.`
   - Los e2e hacen `getByLabel('Auditor', {exact:true}).check()`; así que el nombre accesible del checkbox debe ser exactamente el nombre del perfil, sin la descripción.
6. Campos de alcance condicionales (el rol adicional pide lo mismo que su base: `conBase`, U:234):
   - **Establecimiento (ESM)**: `Select required searchable` si hay perfil 7 (ESM) o un rol con base 7.
     - Opciones: `value=String(p.id)`, label `${sigla ? sigla + ' · ' : ''}${nombre} (${fuerza})`, de `datos.puntos`.
     - Error local: `Elija el establecimiento.`
   - **Regional (OPL regional)**: `Select required` si hay perfil 9 (OPL regional) o un rol con base 9.
     - Opciones: `r.id` → `r.nombre`, de `datos.regionales`.
     - Error local: `Elija la regional.`
   - **Fuerza (Sanidad)**: `Select required` si hay perfil 6 (Sanidad) o un rol con base 6.
     - Opciones: `f.codigo` → `f.nombre`, de `datos.fuerzas`.
     - Error local: `Elija la fuerza.`
   - Las fuerzas son fijas, del servidor (BE `ControladorUsuarios.java:83-84`): `EJC` «DISAN EJC (Ejército)», `ARC` «DISAN ARC (Armada)», `FAC` «JEFSA FAC (Fuerza Aeroespacial)».
7. Solo al crear (`!form.id`): **Switch** con label `Asignar contraseña inicial (se cambia al primer ingreso)` (U:587-596).
   - Los e2e pulsan su TEXTO (`getByText(...).click()`), porque el input del Switch de Mantine está oculto.
8. Si crea y el Switch está activo: **Contraseña inicial** (`PasswordInput`), required, `autoComplete="new-password"`, `maxLength=64`.
   - Description: `Mínimo 12 caracteres, sin tildes ni ñ. El usuario deberá cambiarla en su primer ingreso.`
   - Se valida con `errorClave`.
   - Los e2e la buscan con `getByRole('textbox', {name:'Contraseña inicial'})`.
9. Texto auxiliar (`Text size xs c dimmed`, U:612-616):
   - crear con clave inicial: `Entregue la contraseña inicial al usuario por un canal seguro. No se envía correo.`
   - en los demás casos: `Al crear el usuario se le envía un enlace de un solo uso (60 minutos) para definir su contraseña. Nadie más la conoce.`
10. Botones: «Cancelar» (default, cierra y reinicia la validación) y «Guardar» (`type=submit`, `loading={guardar.isPending}`).

Orden de validación local al enviar (U:201-214): nombre, usuario, correo, alcancePunto, alcanceRegional, alcanceFuerza, claveInicial (solo crear con Switch activo). Si todo pasa, se envía.

Errores del servidor relevantes (BE `identidad/ServicioUsuarios.java`, líneas citadas); salen en el resumen `error-usuario`:
- `Escriba el nombre completo (mínimo 3 caracteres).` (74)
- `Ya existe un usuario con ese nombre de usuario.` (113)
- `Ya existe un usuario con ese correo.` (116)
- `Ya existe un usuario con ese nombre de usuario o ese correo.` (168)
- `El documento y el teléfono tienen máximo 30 caracteres.` (83)
- `El documento es obligatorio para los auditores: va en la notificación previa de la visita (decisión 26).` (91)
- `El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).` (95)
- `El perfil ESM necesita el establecimiento (ESM activo).` (99)
- `El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato).` (103)
- `Contraseña inicial: <p>` (108)
- `No puede quitarse a sí mismo el perfil Administrador.` (150)
- `Hay un perfil no válido.` (318)
- Teléfono (260-262), con `campo: "telefono"`: `El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de las alertas críticas.` El e2e de alertas espera que el resumen contenga «alertas críticas».
- Combinaciones de perfil inválidas: línea 328, texto `m` dinámico (no lo leí).
- Falla: `v.errorServidor(e, 'No se pudo guardar.')` (U:166).

Cuerpo de guardar (U:136-151): ver sección A.3.
Al éxito (U:152-164): `setIrAUsuario(usuario.trim())`, muestra el aviso, `v.reiniciar()`, cierra el formulario e invalida `['usuarios']`.

### A.3 Modal «Asignar contraseña inicial» (U:630-671)
- Título `Asignar contraseña inicial`; `Modal` tamaño por defecto.
- Texto: `<nombre> deberá cambiarla en su próximo ingreso. Si tiene una sesión abierta, se cierra.`
- Resumen con `data-testid="error-clave-inicial"` y etiqueta `clave` = «Contraseña inicial».
- `PasswordInput` label «Contraseña inicial».
  - Description: `Mínimo 12 caracteres, sin tildes ni ñ.`
  - required, `autoComplete="new-password"`, `maxLength=64`.
  - Validación local: `errorClave` (los dos mensajes de la sección 0).
- Botones: «Cancelar» y «Asignar» (`loading=accion.isPending`).
- Éxito: cierra el modal, y el aviso verde es el del servidor.
- Error: `vClave.errorServidor(x, 'No se pudo asignar la contraseña inicial.')`.

### A.4 Modal «Seguridad» (U:673-715)
- Título `Seguridad`.
- Resumen con `data-testid="error-seguridad"`.
  - Etiqueta `minutos` = «Tiempo de inactividad (TIME OUT)».
  - Mapeo de campos: `minutosSesion` → `minutos`.
- Si falla el GET de seguridad: `<Alert color="red">{mensaje}</Alert>`.
- **NumberInput**: label `Tiempo de inactividad (TIME OUT), en minutos`.
  - Description: `Anexo 5, 3.2: la sesión se cierra sola tras este tiempo sin actividad. Aplica desde la siguiente acción de cada usuario.`
  - `min=1`, `max=minutosMaximo ?? 480`, `allowDecimal=false`, `allowNegative=false`, required.
  - `data-testid="minutos-sesion"`. El valor es `minutos ?? seguridad.data?.minutosSesion ?? ''`.
  - El e2e hace `toHaveValue(String(antes))`, `fill(String(otro))`.
  - Validación local (U:679-682): entero entre 1 y `maximo`. Mensaje: `Escriba un número entero de minutos, de 1 a ${maximo}.`
- Botones: «Cancelar» y «Guardar» (`loading=guardarSeguridad.isPending`, `disabled={!seguridad.data}`).
- Éxito: aviso verde, cierra, invalida `['usuarios-seguridad']` y `['sesion']`.
- Errores de servidor (BE `ControladorUsuarios.java:38-41`):
  - `Escriba los minutos de inactividad.`
  - `Los minutos de inactividad deben ser un número entero de 1 a 480.`
- Texto de error por defecto: `No se pudo guardar.`

### A.5 Modal «Nombres de perfiles» (U:717-767)
- Título `Nombres de perfiles`, `size="lg"`; al cerrar reinicia `vPerfiles`.
- Texto: `Los nombres son configurables mientras llega la adenda con el catálogo final de perfiles. Los permisos no cambian.`
- Resumen con `data-testid="error-nombres-perfiles"`; las etiquetas son `perfil<id>` → nombre del perfil, solo los perfiles base (`baseId === null`).
- Por cada perfil base, una fila con un `TextInput` (label = el nombre actual del perfil, `maxLength=60`, flex 1) y un botón «Guardar» (`variant="light"`).
- El botón «Guardar» está deshabilitado si `accion.isPending` o si el texto es igual al nombre actual.
- Validación local: `Escriba el nombre del perfil.`
- Envía `PUT /api/usuarios/perfiles/{id}` con `{nombre, descripcion: p.descripcion}`.
- Un 422 con `campo === 'nombre'` marca el campo de ese perfil (`conCampo`, U:89-91).
- Errores de servidor (BE `ServicioUsuarios.java`): `El nombre del perfil debe tener de 3 a 60 caracteres.` (236), `La descripción tiene máximo 200 caracteres.` (239), `Ya existe otro perfil con ese nombre.` (244).
- Texto por defecto: `No se pudo guardar el nombre.`
- Los perfiles base son: Administrador(1), Coordinador(2), Auditor(3), Consulta(4), DIGSA(5), Sanidad(6), ESM(7), OPL nacional(8), OPL regional(9), Analista(10) (BE `backend\reglas\...\reglas\Perfil.java:12-21`). Pero el listado `perfilesBase` sale del JSON: son los perfiles con `baseId === null`.

### A.6 data-testid de Usuarios
- `usuario-<usuario>` (fila)
- `error-usuario`
- `error-accion-usuario`
- `error-clave-inicial`
- `error-seguridad`
- `error-nombres-perfiles`
- `campo-telefono`
- `minutos-sesion`
- `total-usuarios` (de `Paginacion`)

### A.7 Llamadas HTTP de Usuarios
Todas bajo `@PreAuthorize("@permisos.pantalla('USUARIOS')")` en `BE/identidad/ControladorUsuarios.java:24`.

1. `GET /api/usuarios` (queryKey `['usuarios']`; U:96). Respuesta (BE líneas 56-85):
   - `usuarios[]`: `id`, `nombre`, `documento`, `telefono`, `usuario`, `correo`, `activo`, `ultimoIngreso` (`yyyy-MM-dd HH:mm` en Bogotá o null), `bloqueado`, `sesionAbierta`, `claveInicial`, `perfiles` (`number[]`, ids de perfiles base y roles adicionales), `alcanceFuerza`, `alcancePunto`, `alcanceRegional`.
   - `perfiles[]`: `{id, nombre, descripcion, baseId|null}`, de `perfilesActivos()`.
   - `puntos[]`: `{id, nombre, sigla, fuerza}` (ESM activos, ordenados por fuerza y nombre).
   - `regionales[]`: `{id, nombre}`.
   - `fuerzas[]`: `{codigo, nombre}` (3 fijas).
2. `POST /api/usuarios` (201) o `PUT /api/usuarios/{id}` (200). Cuerpo (U:138-150):
   ```
   {id, nombre, usuario, correo, perfiles: number[],
    alcanceFuerza: string|null, alcancePunto: number|null, alcanceRegional: number|null,
    documento: string|null, telefono: string|null,
    claveInicial: (!f.id && f.conClaveInicial) ? f.claveInicial : null}
   ```
   Respuesta: `Guardado {id, creado, correoOk, mensaje, enlaceLocal}`. La pantalla usa `mensaje`, `enlaceLocal` y `correoOk`.
   En el PUT, el controlador ignora `claveInicial`.
3. `PUT /api/usuarios/{id}/estado` con `{activo: boolean}`. Respuesta `{mensaje}`. Errores del servidor: `No puede inactivar su propio usuario.` (175).
4. `POST /api/usuarios/{id}/restablecer`, sin cuerpo. Respuesta `{mensaje, enlaceLocal?}`.
5. `POST /api/usuarios/{id}/clave-inicial` con `{clave}`. Respuesta `{mensaje}`. Error del servidor: `Contraseña inicial: <p>` (216).
6. `POST /api/usuarios/{id}/cerrar-sesion`, sin cuerpo. Respuesta `{mensaje}`.
7. `POST /api/usuarios/{id}/desbloquear`, sin cuerpo. Respuesta `{mensaje}`.
8. `GET /api/usuarios/seguridad` (`enabled: verSeguridad`). Respuesta `{minutosSesion, minutosMaximo}`.
9. `PUT /api/usuarios/seguridad` con `{minutosSesion}`. Respuesta `{mensaje}`.
10. `PUT /api/usuarios/perfiles/{id}` con `{nombre, descripcion}`. Respuesta `{mensaje}`.
11. Roles: ver sección B.

Efectos entre pantallas:
- Tras guardar, estado y acciones, se invalida `['usuarios']`.
- Tras guardar seguridad, se invalida `['sesion']`.
- Los roles invalidan `['usuarios']` y `['roles-adicionales']`.

### A.8 Permisos y estados de Usuarios
- Solo el Administrador ve el enlace «Usuarios» y accede a la ruta. Los otros perfiles no ven el enlace y la API responde 403.
- Fuentes: el comentario de BE `ControladorUsuarios.java:21` («solo el Administrador»), la protección de ruta `Protegida ruta="usuarios"` (`FE/App.tsx:136`), y las e2e (`usuarios-y-logs.spec.ts:44`, `roles.spec.ts:52,78`, `prueba-en-sitio.spec.ts:28-50`).
- «Desbloquear» solo se muestra con `bloqueado`.
- «Cerrar sesión» solo se muestra con `sesionAbierta`.
- «Contraseña inicial» y «Restablecer contraseña» aparecen siempre, incluso para inactivos.
- «Activar» / «Inactivar» cambia texto, color y aria-label según `activo`.
- La insignia principal es Inactivo si `!activo`, aunque esté bloqueado.
- Todos los botones de acción de la fila se deshabilitan mientras `accion.isPending` (una sola mutación para todas las filas).
- En edición se oculta el Switch y el campo de clave inicial.
- Los campos de alcance dependen de los perfiles marcados.
- «Guardar» del formulario muestra spinner (`loading`).

### A.9 Componentes Mantine usados en Usuarios que Angular no tiene
- **Select** (U:545, 562, 575). El de establecimiento lleva `searchable`; los demás no. El valor es `string|null`.
- **Checkbox.Group / Checkbox** con `description` por opción (U:524-543). El error del grupo se muestra bajo el grupo.
- **Switch** (U:588). Su input está oculto: los e2e pulsan el texto de la etiqueta.
- **NumberInput** (U:689) con `min/max/allowDecimal/allowNegative`, `data-testid` en el input. El valor puede ser `number | string`; los e2e usan `toHaveValue` y `fill`.
- **PasswordInput** (U:598, 647). Se usa a menudo como «campo de clave» (el Angular ya tiene campo de clave, pero con `maxLength` y `autoComplete`).
- **Pagination** de Mantine (`FE/shared/paginacion.tsx:72`), más `getControlProps`.
- **Badge** con variantes `light` / `outline` y colores: green, orange, gray, blue, grape.
- **SimpleGrid** (2 columnas) y **Group / Stack**: layout (no son componentes de formulario).
- `Alert` (error rojo), `Table` (striped, highlightOnHover, `miw`).
- `notifications.show` (`color`, `autoClose: false | 6000`, `message`). Ver el requisito de avisos de éxito: el aviso naranja sin cierre automático es una variante.
- `window.confirm` nativo (4 usos): el e2e usa `page.once('dialog', d => d.accept())`.
- No usan Tabs, DatePicker, Drawer, Menu, Tooltip, Textarea ni SegmentedControl.

---------------------------------------------------------------------
## B. ROLES ADICIONALES (`FE/paginas/RolesAdicionales.tsx`)

### B.1 Estructura
- Componente `RolesAdicionales({abierto, alCerrar})`, montado en U:769. El `useQuery` solo corre con `enabled: abierto`.
- Es un modal: `Modal opened={abierto} title="Roles adicionales" size="xl"` (R:115). El e2e lo localiza con `getByRole('dialog', {name: 'Roles adicionales'})`.
- Dentro del modal (en este orden):
  1. Texto (`size sm`, `c dimmed`): `Anexo 5, 1.12: roles que pida la DIGSA durante el contrato. Cada rol toma el alcance y las reglas de su perfil base y ve solo las pantallas marcadas. Se asignan en el formulario de cada usuario.`
  2. `<Alert color="red">` si falla el catálogo.
  3. `{vEstado.resumen}`, con `data-testid="error-estado-rol"` (error de activar/inactivar).
  4. Botón «Nuevo rol» a la derecha (`Group justify="flex-end"`).
  5. Tabla.
  6. El formulario en línea (no es otro modal), solo si `form !== null`.
- No hay filtros ni paginación en el modal.

### B.2 Tabla (`ScrollArea type="auto"`, `Table striped miw=640 data-testid="tabla-roles"`)
- Aquí NO se usa `TablaAdaptable`, sino `ScrollArea` directo (R:133-134).
- Columnas, en orden: Rol, Perfil base, Pantallas, Usuarios, Acciones.
- Fila vacía: `colSpan=5` con `Aún no hay roles adicionales.` (solo si `catalogo.data?.roles.length === 0`; `size sm`, dimmed).
- Fila: `data-testid="rol-<r.nombre>"`.

| Columna | Celda |
|---|---|
| Rol | `r.nombre` (`Text size sm fw=600`) y, si hay, `r.descripcion` debajo (`size xs`, dimmed) |
| Perfil base | `nombreBase(r.baseId)` (nombre de la base; si no, el id como texto) |
| Pantallas | un `Badge variant="light"` por pantalla, con su título (`tituloPantalla(baseId, codigo)`, o el código si no se halla) |
| Usuarios | `conteo(r.usuarios)` |
| Acciones | ver abajo |

- Acciones (ambos `size="xs" variant="light"`; el segundo deshabilitado con `estado.isPending`):
  - «Editar», `aria-label="Editar el rol <nombre>"`.
  - «Inactivar» (gray) / «Activar» (green), `aria-label` `Inactivar el rol <nombre>` / `Activar el rol <nombre>`.
  - Inactivar pide `window.confirm`: `¿Inactivar el rol <nombre>?` más, solo si `usuarios > 0`, ` Sus <n> usuarios dejarán de ver sus pantallas.`
  - Activar NO pide confirmación.
- Los roles inactivos siguen en la tabla (no hay insignia de estado: solo cambia el botón).
- El orden viene del servidor: `ORDER BY p.activo DESC, p.nombre`.

### B.3 Formulario en línea (R:215-290)
- `<form noValidate data-testid="formulario-rol">`. Título `Text fw=600`: `Editar rol` / `Nuevo rol`.
- Resumen con `data-testid="error-rol"`; etiquetas:
  - nombre = «Nombre del rol»
  - descripcion = «Descripción»
  - baseId = «Perfil base»
  - pantallas = «Pantallas del rol»
- Campos:
  1. **Nombre del rol**: `TextInput`, required, `maxLength=60`.
     - Local: `Escriba el nombre del rol.`
     - Servidor: `El nombre del rol debe tener de 3 a 60 caracteres.` / `Ya existe un perfil o rol con ese nombre.`
  2. **Descripción**: `TextInput`, `maxLength=200`. Sin validación local. Servidor: `La descripción tiene máximo 200 caracteres.`
  3. **Perfil base**: `Select`, required, `disabled={form.id > 0}` (no se cambia al editar).
     - Description: `Define el alcance de los datos y las reglas del rol. No se cambia después de crearlo.`
     - Opciones: `catalogo.bases` (`id` → `nombre`). Solo las bases elegibles; el Select no es searchable.
     - Local: `Elija el perfil base.`
     - Al cambiarla se vacían las pantallas marcadas.
     - Servidor al editar: `El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.`
  4. **Pantallas del rol**: solo aparece si hay base. `Checkbox.Group required`, con un `Checkbox` por pantalla elegible de la base (`value=codigo`, `label=titulo`).
     - Local: `Marque al menos una pantalla.` (solo si hay base y la lista está vacía).
     - Servidor: `Hay una pantalla no válida.`
- Botones: «Cancelar» (cierra solo el formulario, no el modal) y «Guardar rol» (`type=submit`, `loading=guardar.isPending`; no se deshabilita aunque falten pantallas).
- Cuerpo (R:69): `{nombre, descripcion: descripcion || null, baseId: Number(baseId), pantallas: string[]}`.
- Error por defecto: `No se pudo guardar el rol.`
- Éxito: `notifications.show` verde con el mensaje del servidor. Cierra el formulario y refresca roles y usuarios.
- Al cerrar el modal (`cerrar`, R:96-101) se limpian el formulario y los errores.

Avisos de éxito (BE `ControladorUsuarios.java:168,175,182`):
- Crear: `Rol creado. Asígnelo en el formulario de cada usuario.` (201 con `{id, mensaje}`).
- Editar: `Rol actualizado. Aplica desde la siguiente acción de sus usuarios.`
- Estado: `Rol activado (N usuarios activos lo tienen).` / `Rol inactivado (N usuarios activos lo tienen).`
- Error de estado por defecto: `No se pudo cambiar el estado.`

### B.4 Llamadas HTTP (BE `ControladorUsuarios.java`)
- `GET /api/usuarios/roles` (línea 158; queryKey `['roles-adicionales']`; solo si el modal está abierto). Respuesta (`ServicioRoles.catalogo()`, líneas 47-69+):
  - `bases[]`: `{id, nombre (con el nombre renombrado), pantallas: [{codigo, titulo}]}`.
  - `roles[]`: `{id, nombre, descripcion|null, baseId, activo, pantallas: string[] (códigos), usuarios (número de usuarios activos)}`. Los campos exactos de `roles[]` los leí en el SQL (líneas 60-64) y en la interfaz `Rol` (R:21-29); el resto del mapeo no lo leí.
- `POST /api/usuarios/roles` (línea 164; 201).
- `PUT /api/usuarios/roles/{id}` (línea 171).
- `PUT /api/usuarios/roles/{id}/estado` con `{activo: !r.activo}` (línea 178).

### B.5 data-testid de Roles
- `tabla-roles`
- `rol-<nombre>`
- `formulario-rol`
- `error-rol`
- `error-estado-rol`

### B.6 Permisos
Igual que Usuarios: solo el Administrador (el modal se abre desde esa pantalla; la API cae bajo la misma pantalla `USUARIOS`).

### B.7 Componentes Mantine de Roles no existentes en Angular
- **Modal `size="xl"`** (hay modal Angular, pero verificar el tamaño). El modal de Roles contiene un formulario anidado en línea.
- **Select** (no searchable, `disabled`, description).
- **Checkbox.Group / Checkbox** con `label` y `required`.
- **ScrollArea** (`type="auto"`) para la tabla, sin TablaAdaptable.
- **Badge**, **Text** (peso y tamaño), **Group**/**Stack**.
- `window.confirm`.

---------------------------------------------------------------------
## C. LOGS (`FE/paginas/Logs.tsx`)

### C.1 Estructura

**Encabezado** (L:177): `titulo="Logs del sistema"`, `fuente="Anexo 5 · 3 · ítem 15"`. Sin acciones ni descripción.

**Filtros** (`SimpleGrid cols base 1, sm 2, md 4`, con `ref=vFiltros.ref`, L:178-235). Son 7, en este orden:

| # | Etiqueta | Control | Detalles |
|---|---|---|---|
| 1 | Desde | `DatePickerInput` (`@mantine/dates`) | `valueFormat="DD/MM/YYYY"`. Por defecto: hoy menos 7 días (Bogotá) |
| 2 | Hasta | `DatePickerInput` | `valueFormat="DD/MM/YYYY"`. Por defecto: hoy (Bogotá) |
| 3 | Usuario | `Select clearable searchable` | Opciones: `value=String(u.id)`, label `${u.nombre} (${u.usuario})` |
| 4 | Operación | `Select clearable` | Opciones: valor del servidor con la etiqueta de `NOMBRES` (L:60-67), o el valor tal cual si no está |
| 5 | Módulo | `Select clearable searchable` | Opciones: `string[]` de `modulos` del servidor |
| 6 | Tipo | `TextInput` | Description `Exacto, p. ej. usuario_editado`; `maxLength=50` |
| 7 | Texto | `TextInput` | Description `Con texto, el periodo es de máximo 92 días`; `maxLength=80` |

Nombres de operación (`NOMBRES`, L:60-67): `insercion` → «Inserción», `actualizacion` → «Actualización», `eliminacion` → «Eliminación», `consulta` → «Consulta», `ingreso` → «Ingreso», `salida` → «Salida».

Detalles de los filtros:
- Fechas en el estado: `Date | null`. Se envían como `YYYY-MM-DD` con `dayjs`.
- Cambiar Desde o Hasta limpia los errores de `desde` y `texto` del resumen.
- Hay un `useMediaQuery('(max-width: 36em)')` solo para el modal de cambios (`fullScreen`).

**Botones** (`Group`, L:236-253), en orden:
1. «Buscar» (primario).
2. «Descargar Excel» (light).
3. «Descargar CSV» (light).
4. «Enviar por correo» (light).

Debajo: `{vFiltros.resumen}` y `{vDescarga.resumen}`. Luego, `<Alert color="red">` con el error de la lista, y `<Alert color="red">No se pudieron cargar los filtros: <mensaje></Alert>` si fallan las opciones.

**Lo que se ve es lo que se exporta**: los filtros se aplican al pulsar «Buscar» (`aplicado` = `URLSearchParams.toString()`). Tabla, descarga y correo usan siempre `aplicado`, no los campos en edición. Los parámetros se omiten si están vacíos: `desde`, `hasta`, `usuarioId`, `operacion`, `texto` (con `trim`), `modulo`, `tipo` (con `trim`). Inicialmente `aplicado = ''`; la consulta inicial corre sin parámetros (el servidor aplica los últimos 7 días).

**Validación local de «Buscar»** (L:119-130):
- Normaliza fechas: si falta una, usa los últimos 7 días / hoy. Si están invertidas, las intercambia (`.sort()`).
- Si hay `texto` y los días entre ellas son más de 92: error en el campo `texto`, con el mensaje `Para buscar por texto, use un periodo de máximo 92 días.`
- Resumen con `data-testid="error-filtros-logs"`; etiquetas: desde «Desde», hasta «Hasta», texto «Texto».
- Si pasa: `setAplicado(...)` y `setVuelta(v+1)` (fuerza nueva consulta).

**Tabla** (`TablaAdaptable`, `Table striped miw=860 data-testid="tabla-logs"`). Columnas, en orden:

| # | Columna | Celda |
|---|---|---|
| 1 | Fecha y hora | `fechaHora(f.fecha, {segundos: true})` → `dd/mm/aaaa HH:mm:ss` (`whiteSpace: nowrap`) |
| 2 | Usuario | `f.usuario ?? '—'` |
| 3 | Operación | `Badge variant="light"` con `NOMBRES[f.operacion] ?? f.operacion` |
| 4 | Tipo | `f.tipo` (texto plano, p. ej. `usuario_editado`, `login`) |
| 5 | Detalle | `f.descripcion` |
| 6 | IP | `f.ip` |
| 7 | Cambios | solo si `cambios` no es nulo ni vacío: botón «Ver (N)» (`conteo`), `size="xs" variant="light"`, `aria-label="Ver los cambios del registro <id>"` |

- Si no hay filas y no está cargando: `Text c="dimmed"` con `No hay registros con estos filtros.`
- Paginación: NO usa `Paginacion` numerada. Es por cursor con `useInfiniteQuery`, 100 por página (BE `ControladorLogs.POR_PAGINA = 100`, línea 49).
  - Si `hasNextPage`: botón «Ver más» (`variant="default"`, `loading` mientras `isFetchingNextPage`).
  - Cada página nueva se concatena a las anteriores (`pages.flatMap`).
  - El cursor es `siguiente: {fecha, id}`, enviado como `despuesFecha` y `despuesId`.

**Modal «Antes y después»** (L:300-338)
- `Modal title="Antes y después" size="lg" fullScreen={celular}` (pantalla completa por debajo de 36em).
- Línea resumen: `${fechaHora(fecha, {segundos:true})} · ${usuario ?? '—'} · ${tipo}` (`Text size sm`).
- `TablaAdaptable` con `Table striped miw=480 data-testid="tabla-cambios"`.
- Columnas: Campo, Antes, Después.
- Cada fila del detalle:
  - Si `c.oculto`: celda Campo (`c.campo`) y una celda con `colSpan=2` con el texto `Modificado (dato personal: no se guarda el valor, Ley 1581)` (dimmed).
  - Si no: `c.antes ?? '—'` y `c.despues ?? '—'`, con `wordBreak: break-word`.
- Clave de fila: `i + '-' + c.campo`.

**Modal «Enviar logs por correo»** (L:339-363)
- Título `Enviar logs por correo`; abre con `setEnvio(opciones.data?.correoDefecto ?? '')`.
- Resumen con `data-testid="error-envio-logs"`; etiqueta `para` = «Correo de destino».
- `TextInput` label «Correo de destino», `type="email"`.
- Texto: `Se envía el reporte en Excel con los filtros actuales.`
- Un único botón «Enviar» (`type=submit`, `loading={enviando}`); no hay botón Cancelar.
- Validación local `errorCorreo(para, 'Escriba un correo válido para enviar el reporte.', 'Escriba el correo de destino.')`.
- Éxito: `notifications.show` verde con `r.mensaje` y cierra el modal.
- Error: `vEnvio.errorServidor(e, 'No se pudo enviar el correo.')`.
- El e2e pulsa `getByRole('button', {name: 'Enviar', exact: true})`, así que el botón «Enviar por correo» (de afuera) no choca por ser exact.

**Descargas** (L:132-136)
- `descargar('/api/logs/exportar?formato=xlsx|csv&' + aplicado)`.
- Éxito: `vDescarga.reiniciar()`.
- Error: `vDescarga.errorServidor(e, 'No se pudo descargar el archivo.')`.
- Resumen con `data-testid="error-descarga-logs"`.
- Nombre del archivo (BE `ControladorLogs.java:54,191`): `logs_auditoria_yyyyMMdd_HHmm.xlsx` o `.csv`, con la hora de `reloj`.
- Hoja/cabeceras del Excel (BE 189-190): título «Logs de auditoría», columnas `Fecha y hora`, `Usuario`, `Operación`, `Tipo`, `Detalle`, `IP`, `Cambios (antes → después)`. Filtros reflejados: `Periodo`, `Usuario (id)`, `Operación`, `Texto`, `Módulo`, `Tipo`.
- Error del servidor: `El periodo tiene más de 200000 registros. Acótelo (fechas, usuario u operación) y exporte de nuevo.` (línea 170).

### C.2 data-testid de Logs
- `tabla-logs`
- `tabla-cambios`
- `error-filtros-logs`
- `error-descarga-logs`
- `error-envio-logs`
- (La pantalla no tiene `data-testid` por fila.)

### C.3 Llamadas HTTP de Logs (`BE/auditoria/ControladorLogs.java`, `@PreAuthorize("@permisos.pantalla('LOGS')")`, línea 46)
1. `GET /api/logs/opciones` (queryKey `['logs-opciones']`, siempre que se monta la pantalla). Respuesta (líneas 84-90): `{operaciones: string[] (valores: p. ej. insercion, actualizacion, eliminacion, consulta, ingreso, salida), usuarios: [{id, nombre, usuario}], modulos: string[], correoDefecto: string ("" si no hay)}`.
2. `GET /api/logs?desde&hasta&usuarioId&operacion&texto&modulo&tipo[&despuesFecha&despuesId]`. Respuesta (líneas 92-117):
   - `filas[]`: `{id, fecha ("yyyy-MM-dd HH:mm:ss" en Bogotá), fechaLlave, usuario, operacion, tipo, modulo, descripcion, ip, cambios}`.
   - `cambios`: `null` o `[{campo, antes, despues, oculto}]`. Si el JSON es ilegible: `[{campo:"(cambios ilegibles)", oculto:true}]`.
   - `siguiente`: `{fecha, id}` o `null`.
   - También `desde` y `hasta` (los usa la pantalla? No: la interfaz `Pagina` solo declara `filas` y `siguiente`).
   - Errores 422: `La página pedida no es válida.`; `Para buscar por texto, use un periodo de máximo 92 días.`; `El tipo y el módulo son palabras sin espacios (p. ej. usuario_editado o usuarios).` (módulo y tipo se pasan a minúsculas y deben cumplir `^[a-z0-9_.-]{1,50}$`).
3. `GET /api/logs/exportar?formato=xlsx|csv&<filtros>`: archivo adjunto.
4. `POST /api/logs/enviar`, cuerpo (L:152-162): `{desde, hasta, usuarioId (número|null), operacion, texto, modulo, tipo, formato: 'xlsx', para}`.
   - Respuesta `{mensaje: "Reporte enviado a <para>."}`.
   - Errores del servidor: `Escriba un correo válido para enviar el reporte.` (137); `Los reportes solo se envían a correos de: <dominios>.` (142); 502 `No se pudo enviar el correo: <error>` (154).
   - Al enviar, el servidor registra en bitácora `reporte_enviado` (con correo enmascarado).

### C.4 Permisos de Logs
- Solo el Administrador (comentario BE `ControladorLogs.java:42`, `Protegida ruta="logs"` en `FE/App.tsx:138`).
- Los e2e: Analista y los demás perfiles no ven «Logs del sistema» (`roles.spec.ts:77`, `prueba-en-sitio.spec.ts:35,42`).
- Ningún elemento se oculta por estado dentro de la pantalla, salvo: el botón «Ver (N)» (solo si hay `cambios`) y «Ver más» (solo con `hasNextPage`).

### C.5 Componentes Mantine de Logs no existentes en Angular
- **DatePickerInput** (`@mantine/dates`): con `valueFormat="DD/MM/YYYY"`, valor `Date | null`. Está pendiente de definir cómo lo ejercitarán las e2e. Los e2e de Logs NO interactúan con Desde ni Hasta; solo dejan los valores por defecto.
- **Select** (3 usos; `clearable`; 2 con `searchable`). Los e2e de logs abren Módulo con `getByRole('textbox', {name:'Módulo'}).click()` y eligen con `getByRole('option', {name:'usuarios', exact:true})`.
- **TextInput con description**.
- **Modal** con `fullScreen` responsivo (`useMediaQuery`).
- **SimpleGrid** responsivo: 1 / 2 / 4 columnas.
- **Infinite scroll con botón «Ver más»** (no es de Mantine: es `useInfiniteQuery` de TanStack).
- **Badge light**.
- Dependencias de fecha: `dayjs`.

---------------------------------------------------------------------
## D. Casos e2e

Convenciones comunes a todos:
- Todos los specs tienen `test.afterEach(async ({page}) => salir(page))` (cierra la sesión por la API: `POST /api/auth/salir`, con la cabecera CSRF).
- Fixtures de `apoyo.ts`:
  - `ADMIN`: por defecto `admin.e2e:Admin.E2E.Prueba.2026`, o la variable de entorno `DIGSA_E2E_ADMIN` (apoyo.ts:3). Es un usuario sembrado.
  - `ingresar(page, usuario, clave)` (línea 6): `goto('/')`, rellena `getByLabel('Usuario')` y `getByLabel('Contraseña')`, pulsa `Ingresar` (exact); espera el texto `/local: \d{6}/` (el código en modo local, mostrado en el aviso); rellena `[data-testid="codigo"] input` ×6; pulsa `Verificar e ingresar`; espera el botón `Salir` (exact).
  - `unico(prefijo)` (línea 37): `${prefijo}${Date.now() % 1_000_000}${random 0..99}`.
  - `api(page, metodo, ruta, cuerpo)` (línea 57): `fetch` desde la página con CSRF; devuelve `{estado, datos}`.
  - `ingresarConClaveInicial(page, usuario, inicial, nueva)` (línea 78): `Ingresar` con la clave inicial; `getByRole('textbox', {name:'Contraseña nueva', exact:true})`; `getByLabel('Confirme la contraseña nueva')`; pulsa `Cambiar contraseña y continuar`; espera el código `/local:/`; completa el código; `Verificar e ingresar`; espera `Salir`.
  - `ingresarComoAdminPropio(page, prefijo='adm')` (línea 100): con `admin.e2e` (en otro contexto) crea por la API un administrador propio (`perfiles:[1]`, `claveInicial: 'Inicial.Admin.Propio.2026'`, espera 201), cierra la sesión de `admin.e2e`, e ingresa con el nuevo usuario con `ingresarConClaveInicial` (clave propia `Admin.Propio.E2E.2026`). Depende de la sesión única (decisión 7.1).
  - `filaDeUsuario(page, usuario)` (línea 131): `getByLabel('Buscar', {exact:true}).fill(usuario)` y devuelve `getByTestId('usuario-' + usuario)`. Por eso el campo «Buscar» debe tener ese nombre accesible exacto.
  - `sinDesplazamientoHorizontal(page)` (línea 42): sondea `scrollWidth - clientWidth <= 0` hasta 5 s.
- Perfiles por id usados en las e2e: 1 Administrador, 2 Coordinador, 3 Auditor, 10 Analista.

### D.1 `usuarios-y-logs.spec.ts`

**Test 1** (línea 8): «el administrador crea un auditor; el auditor define su contraseña con el enlace e ingresa con su perfil». Depende de datos sembrados: `admin.e2e`. Depende de otras pantallas: la de definir contraseña y el ingreso (`/definir-clave`).
1. `ingresar(page, ...ADMIN)`; clic en el enlace «Usuarios»; clic en el botón «Nuevo usuario».
2. `usuario = unico('auditor')`; `dialogo = getByRole('dialog')`.
3. `dialogo.getByLabel('Nombre completo')` = «Auditor Uno de Prueba»; `getByLabel('Usuario')` = usuario; `getByLabel('Correo')` = `${usuario}@example.com`; `getByLabel('Documento')` = «80123456»; `getByLabel('Auditor', {exact:true}).check()`.
4. Clic en «Guardar». `page.getByText(/Usuario creado/)` visible. Se extrae el enlace con `/local: (http\S+)\)/` del `textContent` del aviso (debe existir: «enlace» truthy).
5. `getByTestId('usuario-' + usuario)` visible.
6. Duplicado: «Nuevo usuario» de nuevo; Nombre «Duplicado», el mismo usuario, correo `otro.${usuario}@example.com`, `getByLabel('Coordinador', {exact:true}).check()`, «Guardar». `getByTestId('error-usuario')` contiene `Ya existe un usuario con ese nombre de usuario.`
7. Segundo contexto del navegador: `goto(pathname+search del enlace)`; rellena `getByLabel('Contraseña nueva')` y `getByLabel('Confirme la contraseña')` con «Auditor.Prueba.2026»; clic en «Guardar contraseña»; `getByTestId('clave-ok')` visible. `ingresar(p2, usuario, ...)`. Visible el enlace «Mis visitas»; el enlace «Usuarios» con `toHaveCount(0)`.
- Nota: el formulario sigue abierto con el error tras el duplicado y el siguiente test sale por `afterEach`. Nota sobre selectores: `getByLabel('Usuario')` sin `exact` dentro del diálogo coincide con «Usuario» y con otros labels que lo contengan (la etiqueta «Usuario» no coexiste con otra que la contenga en este formulario; los otros son «Nombre completo», «Correo»…). En el diálogo de Usuarios no hay otro control con «Usuario» en el nombre accesible, salvo la descripción del propio campo.

**Test 2** (línea 48): «logs: se ven en pantalla con usuario, fecha, operación y detalle; se descargan en Excel y se envían por correo». Depende de datos: `admin.e2e` y su propia bitácora (su ingreso genera `login`).
1. `ingresar` ADMIN; clic en el enlace «Logs del sistema»; clic en «Buscar».
2. `tabla = getByTestId('tabla-logs')`; `tabla.getByRole('cell', {name:'login', exact:true}).first()` visible. Esto requiere que el `tipo` del ingreso sea literalmente `login`.
3. `tabla.getByRole('cell', {name: /^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}$/}).first()` visible (formato dd/mm/aaaa HH:mm:ss).
4. `Promise.all([waitForEvent('download'), clic «Descargar Excel»])`; `suggestedFilename()` coincide con `/^logs_auditoria_\d{8}_\d{4}\.xlsx$/`.
5. Clic en «Enviar por correo»; `getByLabel('Correo de destino')` = `evaluador@example.com`; espera la respuesta cuya URL incluye `/api/logs/enviar`; clic en «Enviar» (exact); `status` 200.
6. `getByText('Reporte enviado a evaluador@example.com.')` visible.
- Depende del entorno: el correo de prueba y los dominios permitidos (`cfg.dominiosReportes`).

**Tests 3 a 5** (línea 66; un bucle con celular 360, tableta 768, escritorio 1280): «responsivo <nombre> (<ancho> px): sin desplazamiento horizontal en ingreso, inicio y logs».
1. `setViewportSize({width: ancho, height: 800})`; `goto('/')`; `sinDesplazamientoHorizontal`.
2. `ingresar(ADMIN)`; `sinDesplazamientoHorizontal`.
3. `goto('/logs')`; `getByRole('heading', {name:'Logs del sistema'})` visible; `sinDesplazamientoHorizontal`.
- Depende de ADMIN. Es relevante para `TablaAdaptable`.

### D.2 `roles.spec.ts`
Constantes: `INICIAL = 'Inicial.E2E.Roles.2026'`, `PROPIA = 'Propia.E2E.Roles.2026'`.

**Test 1** (línea 11): «el administrador crea un rol con base Coordinador y solo Riesgo y Dashboard; el usuario con ese rol ve solo esas pantallas». Depende de datos sembrados: `admin.e2e` y la definición de las pantallas elegibles de Coordinador (los títulos «Dashboard de riesgo» y «Dashboard»; la pantalla «Agenda de visitas» debe existir en la base y no marcarse). Depende de otras pantallas: menú lateral y vistas de otros perfiles.
1. `ingresarComoAdminPropio`; enlace «Usuarios»; botón «Roles adicionales».
2. `roles = getByRole('dialog', {name:'Roles adicionales'})`; clic en «Nuevo rol».
3. `nombreRol = unico('Gestor del riesgo ')`; `form = roles.getByTestId('formulario-rol')`.
4. `form.getByLabel('Nombre del rol')` = nombreRol.
5. `form.getByRole('textbox', {name:'Perfil base'}).click()`; `page.getByRole('option', {name:'Coordinador', exact:true}).click()`. Esto significa que el Select de Mantine se expone como `textbox` con ese nombre, y las opciones se buscan en la página (no dentro del diálogo; van en un portal).
6. `form.getByLabel('Dashboard de riesgo').check()` y `form.getByLabel('Dashboard', {exact:true}).check()`.
7. Clic en «Guardar rol». `page.getByText('Rol creado.')` visible. `roles.getByTestId('rol-' + nombreRol)` contiene «Coordinador».
8. Por la API: `GET /api/usuarios`; busca en `datos.perfiles` el id del rol por nombre (debe existir). `POST /api/usuarios` con `{nombre:'Gestor de Riesgo de Prueba', usuario, correo, perfiles:[rolId], claveInicial: INICIAL}` y espera 201. `salir(page)`.
9. Contexto nuevo: `ingresarConClaveInicial(p, usuario, INICIAL, PROPIA)`. Visibles los enlaces «Dashboard de riesgo» y «Dashboard» (exact). Con `toHaveCount(0)`: «Agenda de visitas» y «Usuarios». `GET /api/agenda/opciones` devuelve 403.

**Test 2** (línea 58): «Analista (ítem 3): consulta nacional, sin agenda ni solicitudes ni administración». Depende de datos sembrados: `admin.e2e`, perfil Analista (id 10), y el menú.
1. `ingresarComoAdminPropio`; `POST /api/usuarios` con `perfiles:[10]` y `claveInicial`, espera 201; `salir`.
2. Contexto nuevo con `ingresarConClaveInicial`. Visibles los enlaces: «Calendario de visitas», «Dashboard de riesgo», «Reportes de visitas», «Prestadores con hallazgos».
3. Ausentes (`toHaveCount(0)`): «Agenda de visitas», «Solicitudes de visita», «Usuarios», «Logs del sistema».

### D.3 `logs-cambios.spec.ts`
**Test** (línea 8): «el administrador edita un usuario y en Logs ve el antes y el después; el dato personal solo dice «modificado»». Depende de datos sembrados: `admin.e2e` y el módulo `usuarios` en `logs/opciones`. Depende de otras pantallas: la API de Usuarios (crear y editar generan los logs).
1. `ingresarComoAdminPropio`. `POST /api/usuarios`: `{nombre:'Persona Original de Prueba', usuario, correo, documento:'52333444', perfiles:[2], claveInicial:'Inicial.E2E.Logs.2026'}`; espera 201.
2. `PUT /api/usuarios/{id}`: `{nombre:'Persona Cambiada de Prueba', usuario: usuario+'b', correo, documento, perfiles:[2]}`; espera 200.
3. Enlace «Logs del sistema». `getByLabel('Tipo', {exact:true}).fill('no_existe')`; «Buscar»; `getByText('No hay registros con estos filtros.')` visible.
4. `getByLabel('Tipo', {exact:true}).fill('usuario_editado')`; `getByRole('textbox', {name:'Módulo'}).click()`; `getByRole('option', {name:'usuarios', exact:true}).click()`; «Buscar».
5. `fila = getByTestId('tabla-logs').getByRole('row').filter({hasText: 'Usuario <nuevo> editado'})`; `toHaveCount(1)`. La descripción de la bitácora debe tener literalmente `Usuario <usuario nuevo> editado`.
6. `fila.getByRole('button', {name: /^Ver los cambios del registro/}).click()`.
7. `cambios = getByTestId('tabla-cambios')`. En la fila que tiene la celda `usuario` (exact): `celdas.nth(1)` `toHaveText(usuario)` y `celdas.nth(2)` `toHaveText(nuevo)`. La fila «nombre» (celda exact `nombre`) contiene «dato personal». Y `cambios` NO contiene «Persona Cambiada».
- Restricciones para la portabilidad: el campo «Tipo» debe tener nombre accesible exacto `Tipo` (el description «Exacto, p. ej. usuario_editado» no debe formar parte del nombre accesible); `getByLabel('Tipo', {exact:true})` solo debe resolver a un elemento (no al «Tipo» del encabezado de columna, que es `th` y no un label).

### D.4 `cuenta-y-seguridad.spec.ts` (casos que tocan Usuarios)
Constantes: `INICIAL = 'Inicial.E2E.Cuenta.2026'`, `PROPIA`, `NUEVA`. Helper `crearAuditor` (línea 14): `POST /api/usuarios` con `{nombre:'Auditor Cuenta de Prueba', usuario, correo, documento:'80111222', perfiles:[3], claveInicial: INICIAL}`; espera 201.

**«cambiar mi contraseña…»** (línea 28): NO toca la pantalla Usuarios. Es de `Mi contraseña` (diálogo `Cambiar mi contraseña`, `data-testid="error-mi-clave"`).

**«el administrador cambia el TIME OUT en Usuarios › Seguridad y lo restablece»** (línea 64). Depende de: `admin.e2e`, parámetros globales del servidor (los restablece en `finally`), `GET /api/sesion` (`minutosInactividad`).
1. `ingresarComoAdminPropio`. `GET /api/usuarios/seguridad` → `antes = minutosSesion`; `otro = antes === 2 ? 3 : 2`.
2. Enlace «Usuarios»; botón «Seguridad».
3. `campo = getByTestId('minutos-sesion')`; `toHaveValue(String(antes))`; `campo.fill(String(otro))`.
4. `getByRole('dialog', {name:'Seguridad'}).getByRole('button', {name:'Guardar'}).click()`.
5. `getByText('Tiempo de inactividad actualizado: <otro> minutos.')` visible. (Es un `getByText` de subcadena: el aviso lleva además «Aplica desde la siguiente acción de cada usuario.»)
6. `GET /api/usuarios/seguridad` → `minutosSesion` = otro. `GET /api/sesion` → `minutosInactividad` = otro.
7. `finally`: `PUT /api/usuarios/seguridad {minutosSesion: antes}`; espera 200.
- El `data-testid` `minutos-sesion` va sobre el `<input>` (el e2e usa `toHaveValue` y `fill`). Atención: `getByRole('button', {name:'Seguridad'})` coincide por subcadena con «Seguridad» solo si no hay otro botón cuyo nombre contenga «Seguridad».

**«un usuario bloqueado por 5 intentos aparece «Bloqueado» y el administrador lo desbloquea»** (línea 85). Depende de la API de ingreso (`/api/auth/inicio`, `/api/auth/ingreso`) y de la regla de bloqueo tras 5 intentos.
1. `ingresarComoAdminPropio`; `crearAuditor` (`usuario`).
2. Contexto nuevo: `goto('/')`; `GET /api/auth/inicio`; 5 × `POST /api/auth/ingreso {usuario, clave: 'Errada.Numero.<i>.X'}`, cada uno espera 401; cierra el contexto.
3. Enlace «Usuarios». `fila = filaDeUsuario(page, usuario)` (escribe en «Buscar»).
4. `fila.getByText('Bloqueado')` visible.
5. `fila.getByRole('button', {name: 'Desbloquear a Auditor Cuenta de Prueba'}).click()` (por `aria-label`; el nombre es el nombre completo del usuario).
6. `getByText('Usuario desbloqueado.')` visible; `fila.getByText('Bloqueado')` con `toHaveCount(0)`.
- El `getByText('Bloqueado')` dentro de la fila es de subcadena: no puede haber otro texto con «Bloqueado» en la fila (el aria-label no cuenta como texto).

**«celular (360 px)»** (línea 106): NO toca Usuarios (Menú y Mi contraseña).

### D.5 `sesion.spec.ts` (casos que tocan Usuarios)
**«ingreso en dos pasos y salir»** (línea 19): tras ingresar con ADMIN, visibles los enlaces «Usuarios» y «Logs del sistema». Depende del menú.

**«sesión única (decisión 7.1)…»** (línea 59): en el equipo A, enlace «Usuarios» y `getByRole('heading', {name:'Usuarios'})` visible (es el `h1` de `EncabezadoPagina`). Es el único paso que toca esta pantalla; el resto depende de las pantallas de ingreso (`error-ingreso` con «ya tiene una sesión abierta»).

**«contraseña inicial y cierre por el administrador…»** (línea 80). Depende de `admin.e2e` (compartido; con sesión única) y del ingreso con clave inicial.
1. `ingresar` ADMIN; enlace «Usuarios»; «Nuevo usuario».
2. `usuario = unico('inicial')`; `dialogo = getByRole('dialog')`: Nombre completo «Usuario Inicial de Prueba», Usuario, Correo, `getByLabel('Coordinador', {exact:true}).check()`.
3. `dialogo.getByText('Asignar contraseña inicial (se cambia al primer ingreso)').click()` (clic en el texto del Switch).
4. `dialogo.getByRole('textbox', {name:'Contraseña inicial'}).fill('Inicial.Asignada.2026')`; «Guardar".
5. `getByText(/Usuario creado con contraseña inicial/)` visible.
6. Contexto nuevo `p2`: `goto('/')`; Usuario y Contraseña (`getByLabel('Contraseña')` con la clave inicial); `Ingresar`; `Contraseña nueva` (textbox, exact) y `Confirme la contraseña nueva` = «Propia.Del.Usuario.2026»; `Cambiar contraseña y continuar`; código `/local: \d{6}/`; `Verificar e ingresar`; espera `Salir`.
7. `page.reload()`; `fila = filaDeUsuario(page, usuario)`; `fila.getByText('Sesión abierta')` visible.
8. `page.once('dialog', d => d.accept())`; `fila.getByRole('button', {name: /Cerrar la sesión de/}).click()`.
9. `getByText('Sesión cerrada.')` visible.
10. `p2.reload()`; `getByTestId('motivo-cierre')` contiene `Su sesión fue cerrada`.
- Aquí hay dos requisitos: el `getByRole('textbox', {name:'Contraseña inicial'})` debe encontrar el campo de clave (por eso no puede resolver también a una etiqueta con ese texto en otro textbox: el Switch tiene un label distinto); y el `getByText('Asignar contraseña inicial (se cambia al primer ingreso)')` debe ser pulsable (activa el Switch).

### D.6 Otros specs que tocan Usuarios o Logs (para portar con los anteriores)
- `alertas.spec.ts:91` — «un teléfono que no es móvil colombiano muestra el error del servidor». Depende de ADMIN.
  1. `ingresar` ADMIN; enlace «Usuarios»; «Nuevo usuario»; `dialogo = getByRole('dialog')`.
  2. Nombre «Usuario Teléfono de Prueba», Usuario, Correo; `getByLabel('Teléfono móvil').fill('601 000 0000')`; `getByLabel('Auditor', {exact:true}).check()`.
  3. `dialogo.getByText(/móvil colombiano: \+57 y 10 dígitos/).first()` visible. Es el error local al salir del campo (el blur ocurre al hacer clic en el checkbox), o la description del campo. Coincide con ambos, por eso `.first()`.
  4. Clic en «Guardar». `getByTestId('error-usuario')` contiene `alertas críticas` (el servidor devuelve la frase `Se usa para los SMS de las alertas críticas.`).
  5. `getByTestId('usuario-' + usuario)` con `toHaveCount(0)` (no se creó).
  - Nota: en este caso no se rellena el Documento, pero el servidor exige el documento al Auditor. El servidor comprueba el teléfono antes que el documento: el test espera el error de teléfono. El orden de las comprobaciones en el servidor es entonces relevante (líneas 83-91 son documento/teléfono máximo y documento de auditor; el teléfono se valida en `telefonoMovil` (260), cuyo orden respecto a la línea 91 no lo comprobé).
- `prueba-en-sitio.spec.ts`:
  - `crearPorPantalla` (línea 55): `getByRole('dialog', {name:'Nuevo usuario'})`, Nombre completo, Usuario, Correo, Documento, `getByLabel(u.perfil, {exact:true}).check()`, clic en el texto del Switch, `getByRole('textbox', {name:'Contraseña inicial'})`, «Guardar», `getByText('Usuario creado con contraseña inicial.')` y `getByTestId('usuario-' + u.usuario)` visibles. Es el único que busca el diálogo por nombre `Nuevo usuario` (el título del Modal).
  - Test «ítem 2» (línea 88): dos administradores crean a la vez 5 usuarios (concurrencia), con perfiles Analista, Auditor, Administrador, OPL…; depende de datos propios.
  - Test «ítem 15» (línea 170): en Logs, `getByLabel('Texto', {exact:true}).fill(SUFIJO)`, «Buscar», `tabla-logs` contiene la celda `usuario_creado` (exact), filas con el usuario y los textos `Inserción`, `Ingreso`, `Salida` (`getByText(..., {exact:true})`, o sea la insignia con ese texto exacto); descarga Excel con el patrón del nombre; «Enviar por correo», `getByLabel('Correo de destino')`, «Enviar» (exact) y `getByText('Reporte enviado a comite.evaluador@example.com.')`.
  - Las comprobaciones de menú por perfil (líneas 28-50): Administrador ve «Usuarios» y «Logs del sistema»; los otros perfiles no.
- `reportes-motor.spec.ts:152` solo menciona «Usuarios y seguridad» como texto de un catálogo (no es la pantalla).

---------------------------------------------------------------------
## E. Resumen de componentes del design system Angular que faltan (resumen consolidado)

Hoy el Angular tiene: botón, botón con motivo, insignia, alerta, campo de texto/clave (input/select/textarea nativos), validación con resumen, tabla adaptable, modal, encabezado de página, ícono, ayuda emergente y avisos de éxito. Para portar estas 3 pantallas faltan o hay que verificar:

| Componente (Mantine) | Dónde | Uso exacto |
|---|---|---|
| Select (con buscador `searchable`, `clearable`, `disabled`, description, `required`, error) | U:545,562,575 (ESM con buscador); R:241 (base, `disabled` al editar); L:201,209,216 (Usuario y Módulo con buscador, Operación sin) | Los e2e lo manejan como `getByRole('textbox', {name})` y las opciones como `getByRole('option', {name, exact})` fuera del diálogo (portal) |
| Checkbox.Group / Checkbox (con `description` y `label` dinámico) | U:524 (perfiles); R:256 (pantallas) | Error de grupo con `conTexto`; los e2e usan `getByLabel(nombre, {exact:true}).check()` |
| Switch | U:588 | Los e2e pulsan el texto de la etiqueta |
| NumberInput | U:689 (`min`, `max`, `allowDecimal=false`, `allowNegative=false`, `data-testid` en el input) | `toHaveValue` y `fill` |
| DatePickerInput | L:179,190 (`valueFormat="DD/MM/YYYY"`, `Date|null`) | Sin interacción e2e |
| Pagination numérica con «Mostrando X–Y de Z usuarios» | U:445 | 25 por página; `data-testid="total-usuarios"`; aria-labels de los controles |
| TextInput con `description`, `maxLength`, `type` (email, tel) y `placeholder` | U, R, L | Existe campo de texto nativo; falta `description` si no lo tiene |
| PasswordInput con `maxLength=64` y `autoComplete` | U:598,647 | |
| Modal (`size lg/xl`, `fullScreen` responsivo) | U:447,630,673,717; R:115; L:300,339 | Los e2e usan `getByRole('dialog', {name})` |
| ScrollArea (tabla de Roles) | R:133 | |
| SimpleGrid responsivo | U:492 (1/2 columnas), L:178 (1/2/4 columnas) | |
| Notificaciones con color orange y `autoClose:false` | U:156-160 | Variante de aviso que no se cierra solo |
| `window.confirm` nativo | U:378,391,431; R:198 | |
| `useInfiniteQuery` con botón «Ver más» | L:105,295 | Paginación por cursor (`despuesFecha`, `despuesId`) |
| Badge (colores green/orange/gray/blue/grape; variantes light/outline) | U:320,329,333,338; R:170; L:277 | |

No aparecen en estas pantallas: Tabs, Drawer, Menu, Tooltip, Textarea, SegmentedControl.

---------------------------------------------------------------------
## F. Puntos de atención para la paridad (hechos observados, sin opinión de diseño)

1. Los e2e buscan botones de fila por `aria-label` con el nombre completo del usuario (p. ej. `Desbloquear a Auditor Cuenta de Prueba`, `Cerrar la sesión de ...`). Los textos visibles son más cortos («Desbloquear», «Cerrar sesión»).
2. El campo «Buscar» de Usuarios debe tener nombre accesible exactamente `Buscar` (`getByLabel('Buscar', {exact:true})`).
3. «Contraseña inicial» aparece tres veces en Usuarios: la insignia de Estado, el botón de fila, y el campo del formulario. Los e2e lo desambiguan con `getByRole('textbox', {name})` dentro del diálogo.
4. En el formulario de usuario, `getByLabel('Usuario')` NO usa `exact`; se resuelve a un solo elemento solo si ningún otro label del diálogo contiene «Usuario».
5. Los labels de los checkboxes de perfil deben ser exactamente el nombre del perfil, con la descripción aparte (`description`), para que `getByLabel('Auditor', {exact:true})` funcione. Hay perfiles cuyo nombre es prefijo de otro («Dashboard» y «Dashboard de riesgo» en las pantallas de Roles): por eso el e2e usa `exact:true`.
6. El Switch se activa pulsando su texto. El input subyacente está oculto en Mantine.
7. El error de servidor del teléfono se muestra en el resumen `error-usuario` y el campo queda marcado; además hay un error local (al perder el foco) con el mismo texto base. El texto local es `El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000).` y el del servidor añade ` Se usa para los SMS de las alertas críticas.`
8. El enlace de definir contraseña aparece en el aviso verde con el formato `(local: http...)`. El e2e extrae `/local: (http\S+)\)/`. En el mismo aviso, el código de acceso local usa `local: \d{6}` en el Ingreso.
9. `ultimoIngreso` en Usuarios se muestra sin segundos; la fecha de Logs, con segundos.
10. Bloqueo y sesión abierta son estados calculados por el servidor al listar (`u.bloqueado(reloj.instant())`; `sesionAbierta` según la huella y la última actividad frente a los minutos del TIME OUT). La pantalla solo los lee.

Lente de la suite (resumen, solo lo observado): autenticación/autorización en el servidor con `@PreAuthorize("@permisos.pantalla('USUARIOS'|'LOGS')")` y CSRF por cabecera `X-XSRF-TOKEN` desde el cliente; las validaciones locales repiten las del servidor, que sigue decidiendo; sesión única por usuario (decisión 7.1) y TIME OUT parametrizable (1 a 480 minutos); modularidad por cliente: no determinado en estas pantallas; microservicios: un solo backend (`backend/app`), la SPA llama a `/api/...` del mismo origen.