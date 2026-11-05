# ADR-004 · Identidad: OIDC con Spring Authorization Server dentro del servicio `seguridad`

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Decide: integradora

## Contexto: lo que hace hoy el monolito (`identidad/`, `reglas/`)
- Ingreso en dos pasos: usuario y contraseña con captcha Cloudflare Turnstile; luego un **código MFA enviado por correo**, con vencimiento e intentos.
- Bloqueo durante 15 minutos tras 5 fallos. **Una sola sesión activa por usuario.** Inactividad configurable por el administrador (`ParametrosSeguridad`).
- Vigencia de la contraseña en días y política de claves (`reglas/Claves`).
- Recuperación por **enlace de un solo uso** al correo: 60 minutos, en base solo su SHA-256, como máximo uno cada 5 minutos.
- Un usuario tiene **perfiles directos** (conjunto) o **roles adicionales**. Un rol adicional = un perfil base (que fija el alcance de datos) + un **subconjunto** de las pantallas de esa base, y **no** da la identidad del perfil base: las autoridades `PERFIL_*` salen solo de los perfiles directos (`reglas/Roles`, `FiltroSesion`). Falla cerrado.
- Se registra cada correo y se guarda su contenido 1 año. El monolito (analítica y presupuesto) debe aceptar el mismo token.

## Opciones
| Criterio | Keycloak | Spring Authorization Server (SAS) |
|---|---|---|
| Código MFA por correo | No es el flujo estándar: requiere un autenticador propio (SPI en Java) | Se porta la lógica actual y `reglas/Mfa` |
| Turnstile, sesión única, textos exactos del monolito | SPI y temas FreeMarker propios | Código Spring, igual al actual |
| Registro y contenido de correos 1 año | Keycloak envía por su propio SMTP: hay que desviarlo a `notificaciones` con otra SPI | Envía por `notificaciones` directamente |
| Perfil base + pantallas | Modelable con grupos y roles, pero se duplica con la administración de usuarios de LUME | Los claims salen de las mismas tablas que administra LUME |
| Operación | Otro producto con su base, actualizaciones y CVE | Una dependencia más de Spring |
| A favor de Keycloak | Consola, federación y protocolos listos | |

## Decisión
**Spring Authorization Server, embebido en `servicios/seguridad`**: una sola pieza y un solo esquema con usuarios, perfiles y credenciales.
- Razón concreta para no separar «identidad» de «seguridad»: el usuario, su bloqueo, su sesión y sus perfiles son la misma entidad. Separarlos obligaría a sincronizar dos bases y a una llamada entre servicios en cada ingreso. Es coherente con ADR-002.
- El emisor (`iss`) es la URL pública del gateway; el gateway enruta las rutas OIDC a `seguridad`. Los servicios obtienen la JWKS por la red interna.
- La pantalla de ingreso es de Angular (`/ingreso`), con los mismos pasos y textos que el monolito. Llama a los endpoints de autenticación de `seguridad` y termina en la autorización OIDC.
- Access token JWT corto (5 min, RS256 con rotación de claves); refresh token rotativo y revocable. Claims: `sub`, `uid` (**el mismo id numérico del monolito**: `seguridad` conserva los id al migrar), `usuario`, `nombre`, `perfiles` (**solo los directos**, en lista), `pantallas`, `asignados`, `alcance` estructurado (fuerza, punto, regional; Analista = consulta nacional), `sid`. **Las autoridades se construyen solo con `perfiles`; la base de un rol nunca produce una autoridad.** `aud` enumera solo el gateway, los servicios y el monolito.
- Sesión única, inactividad y revocación inmediata (hoy `FiltroSesion` revalida en cada petición, y eso no se pierde): el **gateway** mide la inactividad con el parámetro de `seguridad` y comprueba en cada petición el `sid` contra `seguridad` (caché de 30 s como máximo). `seguridad` publica `sesion.cerrada` y `usuario.permisos_cambiados` y el gateway cierra la sesión al recibirlos, sin esperar al vencimiento del JWT. Consultar el estado de la sesión no la renueva.
- Llamadas entre servicios sin usuario (enviar el código MFA, alertas por lotes, exportación al monolito): `client_credentials` con un alcance por operación (por ejemplo `notificaciones:enviar_secreto`). Ningún servicio reenvía el token del usuario salvo donde lo diga su contrato.
- El código MFA y el enlace de recuperación salen por `notificaciones` **con el secreto enmascarado en el contenido guardado** (el monolito no los pasaba por la bandeja de salida precisamente por eso); `notificaciones` no escribe el secreto en claro en ningún log ni mensaje. Alertas críticas: correo y SMS.

## Consecuencias
- (+) Paridad directa con el monolito: aplican las mismas reglas y pruebas.
- (−) El código de seguridad es nuestro: cada fase S1 y S2 pasa por el agente `seguridad` sin hallazgos críticos.
- (−) No hay consola de identidad: la administración es la de LUME, como hoy.
