-- LUME · visitas · maestros: operador logístico, puntos de entrega, auditores y obligaciones del contrato (VISITAS A).
-- Portado de digsa-java (develop @ 1ff82de): V3__catalogos_maestros_checklist (maestros), V6__agenda (puntos.regional_opl y fuente),
-- V8__ajustes_revision_s2 (ix_puntos_fuerza) y V12__flujo_opl (obligaciones_contrato). Mismos nombres de tablas y columnas (ADR-005).
-- Diferencias con el monolito:
--   * puntos.auditor_asignado y auditores.usuario_id no tienen FK: los usuarios son de «seguridad» y aquí son una copia por eventos
--     (usuarios_copia, V2). La aplicación valida contra la copia (auditor activo con perfil Auditor) y falla cerrado.
--   * Sin puntos.centralizador_id (monolito V41): lo escribe presupuesto, que se queda en el monolito (ADR-008).
--   * Un solo operador logístico activo (el monolito lo mantenía solo en el código, ORDER BY id LIMIT 1): índice único parcial.
--   * visitas_obligaciones (V12) es de VISITAS B (ejecución); «ya se calificó en visitas» se consulta por el puerto UsoObligaciones.

CREATE TABLE opl (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre  text NOT NULL,                                            -- razón social del operador logístico
    nit     text,
    correos text[] NOT NULL DEFAULT '{}',                             -- reciben la notificación previa y el acta
    activo  boolean NOT NULL DEFAULT true,
    actualizado timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_opl_activo ON opl ((true)) WHERE activo;

CREATE TABLE puntos (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sigla            text,                                            -- sigla del anexo del OPL (p. ej. DIS-...)
    nombre           text NOT NULL CHECK (char_length(nombre) BETWEEN 3 AND 200),
    unidad           text NOT NULL REFERENCES par_unidades (codigo) ON DELETE RESTRICT,
    fuerza           text NOT NULL REFERENCES par_fuerzas (codigo) ON DELETE RESTRICT,
    modalidad        text REFERENCES par_modalidades (nombre) ON DELETE RESTRICT,   -- solo ESM
    nit              text,                                            -- solo farmacia alterna
    municipio        char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,
    direccion        text,
    nivel            text,
    horario          text,
    contacto         text,
    correos          text[] NOT NULL DEFAULT '{}',                    -- contacto del punto: notificación previa y acta
    regional_id      bigint REFERENCES par_regionales (id) ON DELETE RESTRICT,
    llega_desde      char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,   -- puntos de difícil acceso
    auditor_asignado bigint,                                          -- Anexo 3 p.9; uid de usuarios_copia (sin FK, ver arriba)
    exige_qf         boolean NOT NULL DEFAULT false,                  -- HMC y Hospital Naval de Cartagena (Anexo 3 p.9)
    activo           boolean NOT NULL DEFAULT true,
    creado_por       text,
    creado           timestamptz NOT NULL DEFAULT now(),
    actualizado      timestamptz NOT NULL DEFAULT now(),
    regional_opl     text,                                            -- "Regional N" del anexo del OPL (no es una regional de la interventoría)
    fuente           text,                                            -- de dónde salió el punto (anexo y página, carga masiva o a mano)
    UNIQUE (nombre, unidad),
    CHECK (unidad <> 'ESM' OR modalidad IS NOT NULL)
);
CREATE UNIQUE INDEX uq_puntos_sigla ON puntos (sigla) WHERE sigla IS NOT NULL;
CREATE INDEX ix_puntos_unidad_fuerza ON puntos (unidad, fuerza);
CREATE INDEX ix_puntos_fuerza ON puntos (fuerza);
CREATE INDEX ix_puntos_municipio ON puntos (municipio);
CREATE INDEX ix_puntos_regional ON puntos (regional_id);
CREATE INDEX ix_puntos_llega_desde ON puntos (llega_desde);
CREATE INDEX ix_puntos_auditor_asignado ON puntos (auditor_asignado);

-- Los auditores son usuarios con el perfil Auditor; aquí va lo propio: rol técnico (decisión 2a) y municipio base.
CREATE TABLE auditores (
    usuario_id     bigint PRIMARY KEY,                                -- uid de usuarios_copia (sin FK, ver arriba)
    rol            text NOT NULL DEFAULT 'otro'
                   CHECK (rol IN ('quimico_farmaceutico', 'regente', 'auditor_salud', 'ingeniero_sistemas', 'otro')),
    municipio_base char(5) REFERENCES par_municipios (codigo) ON DELETE RESTRICT,
    actualizado    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditores_municipio_base ON auditores (municipio_base);

-- Obligaciones de la minuta del contrato (parte OBL del formulario): catálogo editable del coordinador que la visita califica.
CREATE TABLE obligaciones_contrato (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prestador text NOT NULL CHECK (prestador IN ('opl', 'fa')),              -- operador logístico o farmacia alterna
    codigo    text NOT NULL UNIQUE CHECK (codigo ~ '^OB-[A-Z0-9-]{1,20}$'),
    texto     text NOT NULL CHECK (char_length(texto) BETWEEN 10 AND 2000),
    fuente    text CHECK (char_length(fuente) <= 200),                     -- cláusula o página de la minuta
    activo    boolean NOT NULL DEFAULT true,
    orden     smallint NOT NULL DEFAULT 0
);

-- ---------- Permisos del rol de la aplicación (monolito V3:233 y V12:218): sin DELETE, los maestros se inactivan ----------
GRANT SELECT, INSERT, UPDATE ON opl, puntos, auditores, obligaciones_contrato TO ${rol_app};
GRANT USAGE ON SEQUENCE opl_id_seq, puntos_id_seq, obligaciones_contrato_id_seq TO ${rol_app};
