# ADR-002 · Servicios de grano grueso

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
~300 usuarios concurrentes y 99,74 % de disponibilidad no exigen una descomposición fina. El monolito está ordenado en paquetes Spring Modulith, pero hay acoplamientos por SQL que hay que cortar (revisión de `develop` @ 1ff82de):
- `motorreportes` lee unas 20 tablas, casi todas de visitas (`visitas*`, `chk_*`, `puntos`, `par_*`, `usuarios`).
- `reportes` es un marco de *plugins* (`ProveedorReporte`), con proveedores en `auditoria`, `correos`, `motorreportes` y `presupuesto`.
- `hallazgos` usa `exportacion` (`EscritorZip`, `IndiceSoportes`), y `exportacion` lee actas, firmas y soportes de visitas.
- `plataforma` (39 clases) la usan todos los módulos: errores, reloj, bitácora, almacén de archivos, exportador, bandeja de salida, correo y SMS.
- `identidad` valida `puntos` y `par_regionales` al crear usuarios ESM u OPL regional; `maestros` y `agenda` leen `usuarios`.

## Decisión
Cinco piezas desplegables:

| Servicio | Lo que absorbe del monolito | Sesión |
|---|---|---|
| `gateway` | Nada (nuevo): borde, BFF, enrutamiento al monolito | integradora |
| `seguridad` | `identidad`, `auditoria`, bitácora · `/api/auth`, `/api/sesion`, `/api/cuenta`, `/api/usuarios`, `/api/logs` · **servidor de autorización OIDC** (ADR-004) | seguridad |
| `visitas` | `agenda`, `ejecucion`, `hallazgos`, `opl`, `maestros`, `catalogos`, `tablero`, **`exportacion`** · `/api/agenda`, `/api/calendario`, `/api/mis-visitas`, `/api/solicitudes`, `/api/propuesta`, `/api/opl-publico`, `/api/maestros`, `/api/catalogos` | visitas-a y visitas-b |
| `reportes` | `motorreportes` y la entrada única `/api/reportes` (catálogo, generación, `reportes_generados`, `/api/reportes/verificar`) | reportes |
| `notificaciones` | `correos`, `alertas`, bandeja de salida, correo y SMS de `plataforma` · `/api/correos`, `/api/alertas` | reportes |

Cómo se cortan los acoplamientos:
- **`plataforma` se reparte.** Errores, reloj, cliente de bitácora (publica eventos de auditoría, ADR-006), interfaz del almacén de archivos y exportador van a `librerias/comun`. La bandeja de salida, el correo y el SMS van a `notificaciones`.
- **El marco de reportes** (`ProveedorReporte`, `DefinicionReporte`) va a `librerias/comun`. `reportes` es la entrada única para el usuario. Los reportes de visitas los calcula sobre una **proyección de visitas** alimentada por eventos (`contratos/eventos/visitas.yaml`, con carga inicial). Los de logs y correos los pide por API a `seguridad` y a `notificaciones`, que siguen dueños de sus datos.
- **`exportacion` va a `visitas`**, junto a los datos y archivos que empaqueta.
- **Copias locales por eventos**: `seguridad` mantiene una copia de puntos y regionales (de `visitas`), y `visitas` y `reportes` mantienen una de usuarios y perfiles (de `seguridad`). Nunca hay tablas compartidas.
- Se quedan en el monolito: `analitica` y `presupuesto` (ADR-008).
- `visitas` es un solo servicio aunque lo construyan dos sesiones; el reparto A/B lo proponen ellas y lo aprueba la integradora.
- **Cambio respecto a la recomendación de partida:** no hay un servicio «identidad» aparte; el servidor de autorización vive dentro de `seguridad` (razón en ADR-004). El puerto 9080 queda reservado, sin uso.

## Consecuencias
- (+) Cinco pipelines y cinco esquemas, operables por un equipo pequeño; la paridad se verifica módulo a módulo.
- (−) `reportes` depende de que la proyección de visitas esté al día: sus reportes muestran la hora del último evento aplicado.
- (−) `visitas` es grande. Si la carga del cierre lo exige se separa, pero no antes.
