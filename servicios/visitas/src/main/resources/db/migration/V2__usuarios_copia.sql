-- LUME · visitas · copia local de usuarios (ADR-002, ADR-006; decisión D2 de la integradora, 2026-11-06).
-- Los usuarios son de «seguridad». Aquí llega una copia, por los eventos usuario.* (con carga inicial y conciliación por
-- /interno/usuarios/exportacion), con SOLO los campos que usa visitas:
--   * nombre, usuario, correo y documento: equipo auditor de la notificación previa (monolito RepositorioVisitas.java:214) y lista de
--     auditores de Maestros (ServicioMaestros.java:313). El documento nunca sale en logs ni en la bitácora (solo «modificado»).
--   * activo y perfiles directos: «auditor activo con perfil Auditor», coordinadores a quienes avisar y conteos por perfil.
--   * alcance: fuerza, punto y regional (conteos antes de eliminar o inactivar; alcance de solicitudes).
-- No hay FK desde puntos ni auditores hacia esta tabla: la copia es eventual y la integridad la valida la aplicación (falla cerrado).
-- Mientras no lleguen los eventos de S2, la llena /interno/pruebas/usuarios, que solo existe en los perfiles local y prueba.

CREATE TABLE usuarios_copia (
    uid              bigint PRIMARY KEY,                               -- el id del monolito (ADR-004)
    usuario          text NOT NULL,
    nombre           text NOT NULL,
    documento        text,
    correo           text,
    activo           boolean NOT NULL,
    perfiles         smallint[] NOT NULL DEFAULT '{}',                 -- perfiles directos (reglas/Perfil.id)
    alcance_fuerza   text,
    alcance_punto    bigint,
    alcance_regional bigint,
    version          bigint NOT NULL CHECK (version >= 0),             -- versión del agregado: se descartan los eventos viejos (ADR-006)
    actualizado      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_usuarios_copia_usuario ON usuarios_copia (usuario);
CREATE INDEX ix_usuarios_copia_alcance_fuerza ON usuarios_copia (alcance_fuerza) WHERE alcance_fuerza IS NOT NULL;
CREATE INDEX ix_usuarios_copia_alcance_punto ON usuarios_copia (alcance_punto) WHERE alcance_punto IS NOT NULL;
CREATE INDEX ix_usuarios_copia_alcance_regional ON usuarios_copia (alcance_regional) WHERE alcance_regional IS NOT NULL;

-- Idempotencia de los consumidores (ADR-006): el id de cada evento aplicado.
CREATE TABLE mensajes_procesados (
    id         uuid PRIMARY KEY,
    tipo       text NOT NULL,
    procesado  timestamptz NOT NULL DEFAULT now()        -- se purga lo más viejo que la ventana de reentrega del broker
);

-- La copia no se borra: un usuario dado de baja llega como activo = false.
GRANT SELECT, INSERT, UPDATE ON usuarios_copia TO ${rol_app};
CREATE INDEX ix_mensajes_procesados_procesado ON mensajes_procesados (procesado);
GRANT SELECT, INSERT, DELETE ON mensajes_procesados TO ${rol_app};   -- DELETE: solo la purga periódica
