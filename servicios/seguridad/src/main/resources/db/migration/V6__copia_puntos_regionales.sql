-- LUME · seguridad · copia local de puntos (ESM) y regionales, dueños: el servicio «visitas» (ADR-002, ADR-005, D5 de la integradora).
-- Solo para validar el alcance de los usuarios ESM y OPL regional y para mostrar las opciones del formulario (en el monolito, consultas
-- directas a puntos y par_regionales: ServicioUsuarios.existeActivo y opcionesAlcance). Las alimentan los eventos de visitas (oleada 2)
-- con su carga inicial y conciliación por API; mientras no lleguen, están vacías y crear un usuario ESM u OPL regional falla cerrado
-- (422). Semilla ficticia solo en el perfil local y en pruebas, nunca por migración.
-- Sin CHECK de formato sobre datos ajenos: un evento válido en visitas no debe quedar envenenado aquí (el consumidor valida y registra).
-- usuarios.alcance_fuerza (V1) sí conserva su CHECK.
CREATE TABLE puntos_copia (
    id          bigint PRIMARY KEY,                         -- el id de visitas (el mismo del monolito)
    nombre      text NOT NULL,
    sigla       text,
    fuerza      text,
    unidad      text NOT NULL,                              -- 'ESM' son los establecimientos que ve el perfil ESM
    activo      boolean NOT NULL,
    version     bigint NOT NULL DEFAULT 0,                  -- versión del agregado en visitas (ADR-006: no se aplica una anterior)
    actualizado timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_puntos_copia_esm ON puntos_copia (fuerza, nombre) WHERE activo AND unidad = 'ESM';

CREATE TABLE regionales_copia (
    id          bigint PRIMARY KEY,
    nombre      text NOT NULL,
    activo      boolean NOT NULL,
    version     bigint NOT NULL DEFAULT 0,
    actualizado timestamptz NOT NULL DEFAULT now()
);

-- Permisos del rol de la aplicación (D8): el consumidor de eventos de visitas (S2, en este mismo servicio) las escribe; nunca se borran
-- (se inactivan) y el id no cambia.
GRANT SELECT, INSERT ON puntos_copia, regionales_copia TO ${rol_app};
GRANT UPDATE (nombre, sigla, fuerza, unidad, activo, version, actualizado) ON puntos_copia TO ${rol_app};
GRANT UPDATE (nombre, activo, version, actualizado) ON regionales_copia TO ${rol_app};
