-- LUME · visitas · checklist versionado (VISITAS A). La versión v6 la publica V5.
-- Portado de digsa-java (develop @ 1ff82de): V3__catalogos_maestros_checklist (parte del checklist), sin cambios en tablas ni columnas.
-- Una versión publicada no se modifica; cada visita queda ligada a la suya (visitas.checklist_version). Lo leen agenda (programar exige
-- una versión vigente), ejecución (formulario, VISITAS B), tablero y reportes.

CREATE TABLE chk_versiones (
    version          text PRIMARY KEY,
    descripcion      text,
    fecha_generacion date NOT NULL,
    vigente          boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uq_chk_versiones_vigente ON chk_versiones (vigente) WHERE vigente;   -- una sola vigente

CREATE TABLE chk_vacios (
    version text NOT NULL REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    codigo  smallint NOT NULL,
    nombre  text NOT NULL,                                            -- vacío de mayor riesgo (Res. 1403 frente a Res. 3100)
    PRIMARY KEY (version, codigo)
);

CREATE TABLE chk_guia_obligacion (
    version   text PRIMARY KEY REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    cumple    text NOT NULL,
    no_cumple text NOT NULL,
    no_aplica text NOT NULL,
    fuente    text
);

CREATE TABLE chk_componentes (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version     text NOT NULL REFERENCES chk_versiones (version) ON DELETE RESTRICT,
    codigo      text NOT NULL,                                        -- C00, C01, ...
    nombre      text NOT NULL,
    descripcion text,
    grupo       text,
    especial    text,                                                 -- plan = solo con plan de mejoramiento vigente o seguimiento
    escala      jsonb,                                                -- escala propia (habilitación); NULL = Cumple / No cumple / No aplica
    guia        text,
    unidades    text[] NOT NULL CHECK (unidades <@ ARRAY['ESM', 'FA', 'NC']::text[] AND cardinality(unidades) > 0),
    fuente      text,
    orden       smallint NOT NULL DEFAULT 0,
    UNIQUE (version, codigo)
);

CREATE TABLE chk_items (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    componente_id bigint NOT NULL REFERENCES chk_componentes (id) ON DELETE RESTRICT,
    codigo        text NOT NULL,                                      -- HB-01, P05-07, ...
    punto         text NOT NULL,
    criterio      text NOT NULL,
    fuente        text,
    origen        text NOT NULL,
    requisito     text,
    evidencia     text,
    aplica        text,
    condicion     text,                                               -- con condición se admite No aplica en escalas propias
    prioridad     text,                                               -- vacío de mayor riesgo al que pertenece
    medicion      jsonb,
    subvalidacion jsonb,                                              -- soporte de inscripción en el REPS
    orden         smallint NOT NULL DEFAULT 0,
    UNIQUE (componente_id, codigo)
);

-- Permisos del rol de la aplicación (monolito V3:229): solo lectura; una versión publicada no se modifica.
GRANT SELECT ON chk_versiones, chk_vacios, chk_guia_obligacion, chk_componentes, chk_items TO ${rol_app};
