# Decisiones de negocio pendientes del usuario

Lo que la integradora o una sesión detectó y que **no** es técnico: lo decide el usuario. Se pregunta en el momento indicado.

| # | Detectado | Tema | Contexto | Cuándo decidir |
|---|---|---|---|---|
| U-1 | 2026-11-06, visitas-a (M5) y seguridad | **Supresión de datos personales (Ley 1581)** (pendiente) | El monolito no tiene baja ni anonimización de usuarios: solo los inactiva (revisado `identidad/ServicioUsuarios` y `RepositorioUsuarios`). Si se quiere atender el derecho de supresión, la técnica ya está lista: la foto de `usuario.*` admite `documento`/`correo` en null y la anonimización saldría como `usuario.actualizado` con versión mayor, que limpia todas las copias (visitas, monolito, reportes). Falta decidir si se ofrece, quién la ejecuta y qué pasa con la bitácora y las actas que ya nombran a la persona. Inventario de dónde queda el dato: tabla del servicio dueño, copias de cada consumidor, `outbox.cuerpo` (hasta 7 días tras publicarse, luego se purga), colas y DLQ de cada consumidor, bitácora y correos guardados 1 año. | Cierre (I9) |

## Decididas por el usuario (cambian el comportamiento del monolito)

| Fecha | Tema | Decisión | Dónde |
|---|---|---|---|
| 2026-11-06 | Textos de botones por WCAG 2.5.3 | «Restablecer la contraseña», «Cerrar la sesión», «Ver (N) cambios del registro M» | angular-base A5, `DESVIACIONES_A11Y.md` |
| 2026-11-06 | D-N1 · Cierre de la visita y correo del acta | La visita se cierra al generar el acta; el correo sale después por evento a notificaciones, con reintentos y alerta al administrador si se agotan | visitas-b VB1 |
| 2026-11-06 | D-N2 · Exportación completa | Solo de visitas, mismo formato (CSV, LEEME, índice con huellas); el ZIP consolidado de todos los servicios queda para el cierre | visitas-b VB2 |
| 2026-11-06 | Office con macros u objetos activos | Se **rechazan** («no es de un tipo admitido»): genérico `x-tika-msoffice` y OOXML con `vbaProject.bin`, `activeX/` o `embeddings/`. El monolito los aceptaba | visitas-a VA2.0, `librerias/comun` (ValidadorArchivos) |
