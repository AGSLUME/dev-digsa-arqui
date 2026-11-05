# ADR-007 · Contratos: contract-first con OpenAPI, cliente Angular generado

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
La sesión Angular avanza en paralelo con las de backend. Sin un contrato acordado antes, una de las dos espera a la otra o se desalinean. El comportamiento del monolito (rutas, códigos, mensajes) es la especificación.

## Decisión
- **Un archivo OpenAPI 3.1 por servicio** en `contratos/`: `seguridad.yaml`, `visitas.yaml`, `reportes.yaml`, `notificaciones.yaml`. Los eventos van en `contratos/eventos/` (ADR-006).
- **Primero el contrato, luego el código.** Las rutas, cuerpos, códigos HTTP y textos de error se toman del monolito; cualquier diferencia se justifica en el propio contrato.
- Errores comunes: un esquema `Problema` (RFC 7807: `type`, `title`, `status`, `detail`, `instance`, más `motivo` y `errores` por campo) definido una vez en `contratos/comun.yaml` y referenciado por todos.
- Backend: `openapi-generator-maven-plugin` (versión fija) genera **interfaces** Spring (`interfaceOnly`) y DTO; el controlador implementa la interfaz, de modo que si el código se aparta del contrato, no compila.
- Frontend: el cliente Angular (`typescript-angular`) se **genera** desde `contratos/` hacia `frontend/src/app/api/` en cada build; no se escribe a mano ni se versiona lo generado.
- Validación en CI: lint del contrato (Spectral o Redocly, con versión fija) y detección de cambios incompatibles contra `develop` (`openapi-diff`). Un cambio incompatible exige subir la versión del contrato y aviso a la integradora.
- Propiedad: cada contrato lo edita la sesión dueña del servicio. Si otra sesión necesita un cambio, lo pide; la integradora resuelve los conflictos.

## Consecuencias
- (+) Angular puede trabajar contra un mock generado del contrato (Prism u otro, con versión fija) antes de que exista el servicio.
- (+) El contrato documenta la API sin esfuerzo adicional.
- (−) Hay que mantener la disciplina: ningún endpoint fuera del contrato (lo vigila el revisor en cada merge).
