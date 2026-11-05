# ADR-008 · Convivencia con el monolito digsa-java (analítica y presupuesto)

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto
Analítica y presupuesto no se migran. Hoy el monolito autentica con sesión propia (`/api/auth`, Spring Session JDBC). Revisión del código (`develop` @ 1ff82de):
- Exponen `/api/analitica/**` (54 rutas) y `/api/presupuesto/**` (26 rutas).
- Usan `identidad.UsuarioSesion` como principal (34 usos), `plataforma.RegistroBitacora` (27), el motor de `reportes`, `alertas` y `correos`.
- **Leen directamente las tablas `usuarios` (17 consultas) y `perfiles`**, y también maestros que pasan a `visitas`: `puntos` (20), `par_fuerzas`, `par_municipios`, `par_departamentos`, `par_regionales`, `par_modalidades`, `par_parametros`.
- Presupuesto escribe directamente en la bandeja de correos (`correos_salida`) y analítica lee `alertas_eventos` y `log_transacciones`.

## Decisión
1. **Enrutamiento**: el gateway envía `/api/analitica/**` y `/api/presupuesto/**`, y la interfaz React de esas dos áreas, al monolito, con `TokenRelay`. El resto de rutas del monolito **no** se exponen por el gateway. **El monolito solo acepta tráfico del gateway** (red interna; mTLS en el cierre), y su `/api/auth/**` y su cadena de sesión propia se desactivan para los usuarios migrados: así nadie entra directo al monolito con una clave antigua o con un usuario ya inactivado. La URL del monolito se configura en `.env` (`DIGSA_MONOLITO_URL`); en local no se usan los puertos 8080 y 8081.
2. **Autenticación en el monolito**: se añade una cadena de seguridad *resource server* (JWT, mismo emisor y JWKS que los servicios) solo para esas rutas. Arma `UsuarioSesion(uid, usuario, nombre)` con autoridades `PERFIL_*` **solo de `perfiles` (directos)** y `UsuarioSesion.Acceso(alcance, pantallas, asignados)` desde los claims, igual que hoy `FiltroSesion`. Las revalidaciones que analítica hace contra la base (por ejemplo `perfiles.alcanceVigente` en las descargas diferidas) siguen funcionando sobre la copia del punto 3. Lo entrega la sesión seguridad en S4 **como parche y pasos**; **no se hace commit en digsa-java** sin decisión explícita.
3. **Usuarios y perfiles en el monolito**: se mantienen como **copia** de `usuarios`, `usuarios_perfiles`, `perfiles` y el estado activo, con **los mismos id** (`seguridad` conserva los del monolito; si no, los `usuario_id` de analítica quedarían atribuidos a otra persona). Se sincroniza por los eventos `usuario.*` (ADR-006) en menos de 1 minuto, más una conciliación nocturna contra un endpoint de exportación de `seguridad`. Igual con **maestros y catálogos** (`puntos`, `par_*`), alimentados por los eventos de `visitas`. Solo el sincronizador escribe esas tablas; las pantallas del monolito dejan de administrar usuarios y maestros.
4. **Datos compartidos solo por API**: el monolito no lee esquemas de los servicios nuevos ni viceversa.
5. **Bitácora, correos y alertas** de analítica y presupuesto siguen en el monolito mientras convivan, con su retención de 1 año (V92). **Limitación aceptada por el usuario el 2026-11-05:** hay dos registros de correo y de auditoría, y `/api/correos` y `/api/logs` del sistema nuevo no muestran los de analítica y presupuesto. Unificarlos queda fuera de alcance salvo que el usuario lo pida.
6. **Versión del monolito**: el parche corre sobre Boot 3.5, Security 6 y Jackson 2. La validación JWT del monolito es código propio y **no reutiliza `librerias/comun`** (Boot 4, ADR-012).
7. **Interfaz**: Angular muestra las entradas de menú de analítica y presupuesto según `pantallas` y navega a la interfaz React servida por el monolito bajo el mismo origen del gateway, así que la cookie del BFF sirve sin volver a ingresar.

## Consecuencias
- (+) Un solo ingreso para todo LUME; el monolito cambia solo en su borde de seguridad.
- (−) Dos interfaces (Angular y React) con el mismo design system LUME durante la convivencia.
- (−) Riesgo: que la copia de usuarios quede desactualizada (por ejemplo, un usuario inactivado que sigue activo en el monolito). Se mitiga con el evento, la conciliación y la revocación de la sesión en el gateway (ADR-004), y se prueba en S4 con el e2e de analítica.
- (−) El monolito necesita un consumidor de RabbitMQ (el sincronizador): forma parte del parche de S4.
- (−) La interfaz React de analítica y presupuesto llama además a rutas que ahora son de los servicios nuevos (`/api/sesion`, `/api/catalogos`, ...). S4 inventaría esas llamadas y comprueba que los contratos nuevos las cubren; si no, se adapta el cliente React dentro del parche.
- (−) El parche del monolito requiere la aprobación del usuario antes de aplicarse en digsa-java.
