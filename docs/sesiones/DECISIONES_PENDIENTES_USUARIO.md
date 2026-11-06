# Decisiones de negocio pendientes del usuario

Lo que la integradora o una sesión detectó y que **no** es técnico: lo decide el usuario. Se pregunta en el momento indicado.

| # | Detectado | Tema | Contexto | Cuándo decidir |
|---|---|---|---|---|
| U-2 | 2026-11-06, reportes | **Proveedor real de SMS** (pendiente) | El monolito no tiene proveedor contratado. Mientras tanto el SMS de alertas críticas se registra sin enviarse (adaptador «ninguno»/archivo); en producción el servicio no arranca sin proveedor. Elegir el proveedor (agregador con API HTTP) | Cierre (I9) |
| U-1 | 2026-11-06, visitas-a (M5) y seguridad | **Supresión de datos personales (Ley 1581)** (pendiente) | El monolito no tiene baja ni anonimización de usuarios: solo los inactiva (revisado `identidad/ServicioUsuarios` y `RepositorioUsuarios`). Si se quiere atender el derecho de supresión, la técnica ya está lista: la foto de `usuario.*` admite `documento`/`correo` en null y la anonimización saldría como `usuario.actualizado` con versión mayor, que limpia todas las copias (visitas, monolito, reportes). Falta decidir si se ofrece, quién la ejecuta y qué pasa con la bitácora y las actas que ya nombran a la persona. Inventario de dónde queda el dato: tabla del servicio dueño, copias de cada consumidor, `outbox.cuerpo` (hasta 7 días tras publicarse, luego se purga), colas y DLQ de cada consumidor, bitácora y correos guardados 1 año. | Cierre (I9) |

## Decididas por el usuario (cambian el comportamiento del monolito)

| Fecha | Tema | Decisión | Dónde |
|---|---|---|---|
| 2026-11-06 | Textos de botones por WCAG 2.5.3 | «Restablecer la contraseña», «Cerrar la sesión», «Ver (N) cambios del registro M» | angular-base A5, `DESVIACIONES_A11Y.md` |
| 2026-11-06 | D-N1 · Cierre de la visita y correo del acta | La visita se cierra al generar el acta; el correo sale después por evento a notificaciones, con reintentos y alerta al administrador si se agotan | visitas-b VB1 |
| 2026-11-06 | D-N2 · Exportación completa | Solo de visitas, mismo formato (CSV, LEEME, índice con huellas); el ZIP consolidado de todos los servicios queda para el cierre | visitas-b VB2 |
| 2026-11-06 | Office con macros u objetos activos | Se **rechazan** («no es de un tipo admitido»): genérico `x-tika-msoffice` y OOXML con `vbaProject.bin`, `activeX/` o `embeddings/`. El monolito los aceptaba | visitas-a VA2.0, `librerias/comun` (ValidadorArchivos) |
| 2026-11-06 | Reenviar acta | Si el correo del acta se agota, la visita cerrada muestra «Reenviar acta» (coordinador y líder): mismo PDF a los destinatarios actuales, tras corregir el correo en Maestros, sin reabrir la visita | visitas-b VB1.4 |
| 2026-11-06 | Mensaje al generar el acta (consecuencia de D-N1) | «Acta generada y visita cerrada: el correo sale a N destinatarios.» en lugar de «Acta enviada a N destinatarios»; la e2e se adapta | visitas-b VB1.4 |
| 2026-11-06 | Enlace del OPL en el correo del acta | El correo conserva el enlace (como el monolito); por eso `visitas` guarda el token **cifrado** (AES-GCM, `VISITAS_OPL_CLAVE`) y lo entrega solo a `notificaciones` por `/interno/avisos/acta/{id}` | visitas-b VB1.4, notificaciones |
| 2026-11-06 | Perfiles por carga masiva | Solo perfiles de alcance **local** (ESM, OPL, Sanidad, Auditor…). Los nacionales (Coordinador, DIGSA, Analista) solo desde la pantalla Usuarios. El monolito permitía todos menos Administrador | seguridad `/interno/usuarios/importar`, visitas-a VA2b |
| 2026-11-06 | Reactivar por carga masiva | La carga **no** reactiva a un usuario inactivo: la fila sale con aviso y se reactiva solo desde Usuarios. El monolito sí reactivaba | seguridad `/interno/usuarios/importar` |
| 2026-11-06 | Reportes de presupuesto | Salen del catálogo de «Reportes» del sistema nuevo y siguen solo en Presupuesto › Reportes del monolito, **sin código de verificación**, mientras presupuesto siga en el monolito | reportes R2 |
| 2026-11-06 | Riesgo aceptado: enlace del OPL en el PDF del acta | El PDF (URL y QR con el token) se adjunta a todos los destinatarios y lo descargan el equipo y el coordinador, como en el monolito. El cifrado en la base no cubre ese canal. Mitigación: responder exige además el código de 6 dígitos que llega solo al correo del OPL, y el enlace vence con el plazo del plan, al reabrir o con un acta nueva | visitas-b, notificaciones |
