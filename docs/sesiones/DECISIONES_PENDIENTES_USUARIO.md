# Decisiones de negocio pendientes del usuario

Lo que la integradora o una sesión detectó y que **no** es técnico: lo decide el usuario. Se pregunta en el momento indicado.

| # | Detectado | Tema | Contexto | Cuándo decidir |
|---|---|---|---|---|
| U-1 | 2026-11-06, visitas-a (M5) y seguridad | **Supresión de datos personales (Ley 1581)** | El monolito no tiene baja ni anonimización de usuarios: solo los inactiva (revisado `identidad/ServicioUsuarios` y `RepositorioUsuarios`). Si se quiere atender el derecho de supresión, la técnica ya está lista: la foto de `usuario.*` admite `documento`/`correo` en null y la anonimización saldría como `usuario.actualizado` con versión mayor, que limpia todas las copias (visitas, monolito, reportes). Falta decidir si se ofrece, quién la ejecuta y qué pasa con la bitácora y las actas que ya nombran a la persona. Inventario de dónde queda el dato: tabla del servicio dueño, copias de cada consumidor, `outbox.cuerpo` (hasta 7 días tras publicarse, luego se purga), colas y DLQ de cada consumidor, bitácora y correos guardados 1 año. | Cierre (I9) |
