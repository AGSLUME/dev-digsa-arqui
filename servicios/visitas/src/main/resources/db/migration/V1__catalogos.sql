-- LUME · visitas · catálogos de la operación (VISITAS A, rango V1–V199).
-- Portado de digsa-java (develop @ 1ff82de): V3__catalogos_maestros_checklist (parte de catálogos). Se conservan nombres de tablas y
-- columnas para la paridad (ADR-005). Los datos los cargan V6__datos_iniciales (una vez) y R__catalogos (repetible).
-- Diferencias con el monolito:
--   * Las FK de usuarios.alcance_* hacia estas tablas no existen: los usuarios son de «seguridad», que mantiene su copia de fuerzas,
--     puntos y regionales por eventos (ADR-002). Lo que se cuenta aquí sale de usuarios_copia (V2).
--   * GRANT explícitos por tabla al rol de la aplicación (D8, ADR-005), los mismos que concedía el monolito (V3:223-235).

-- ---------- Catálogos geográficos y de la operación ----------
CREATE TABLE par_departamentos (
    codigo char(2) PRIMARY KEY,
    nombre text NOT NULL
);

CREATE TABLE par_municipios (
    codigo       char(5) PRIMARY KEY,                        -- código DANE
    departamento char(2) NOT NULL REFERENCES par_departamentos (codigo) ON DELETE RESTRICT,
    nombre       text NOT NULL,
    latitud      numeric(9, 6),                              -- DANE (datos.gov.co, gdxc-w37w): cercanía de la propuesta de agenda
    longitud     numeric(9, 6)
);
CREATE INDEX ix_municipios_departamento ON par_municipios (departamento);

CREATE TABLE par_unidades (
    codigo text PRIMARY KEY CHECK (codigo IN ('ESM', 'FA', 'NC')),   -- ESM, farmacia alterna, nivel central del OPL
    nombre text NOT NULL,
    fuente text,
    orden  smallint NOT NULL DEFAULT 0
);

-- Fuerzas: catálogo editable por el coordinador (Anexo 5, prueba 4: se crean, modifican y eliminan en vivo).
CREATE TABLE par_fuerzas (
    codigo  text PRIMARY KEY CHECK (codigo ~ '^[A-Z]{2,5}$'),       -- EJC, ARC, FAC; NA = nivel central del OPL
    nombre  text NOT NULL CHECK (char_length(nombre) BETWEEN 2 AND 120),
    sanidad text,                                                     -- DISAN o JEFSA de la fuerza
    correos text[] NOT NULL DEFAULT '{}',                             -- destinatarios de la notificación previa y del acta
    fuente  text,
    orden   smallint NOT NULL DEFAULT 0,
    activo  boolean NOT NULL DEFAULT true
);

-- Regionales de la interventoría (8 equipos regionales, respuesta AGS-21). Nunca se borran: se inactivan.
CREATE TABLE par_regionales (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE CHECK (char_length(nombre) BETWEEN 2 AND 80),
    activo boolean NOT NULL DEFAULT true
);

CREATE TABLE par_modalidades (
    nombre text PRIMARY KEY,                                          -- Dispensación, Suministro, Consignación, Satélite
    orden  smallint NOT NULL DEFAULT 0
);

CREATE TABLE par_tipos_visita (
    codigo       text PRIMARY KEY,
    nombre       text NOT NULL,
    unidad       text REFERENCES par_unidades (codigo) ON DELETE RESTRICT,   -- NULL = cualquiera
    modalidad    text,
    aviso        text NOT NULL CHECK (aviso IN ('opl', 'no-opl', 'modulo')), -- opl: 15 días (Anexo 3 p.9); no-opl: sin aviso; modulo: N hábiles
    periodicidad text,
    finalidad    text,
    notificacion text,
    fuente       text,
    orden        smallint NOT NULL DEFAULT 0
);

-- Solo las claves de visitas (decisión D6): las de presupuesto se quedan en el monolito y las de bandeja y alertas van a «notificaciones».
CREATE TABLE par_parametros (
    clave       text PRIMARY KEY,
    valor       text,
    descripcion text,
    fuente      text,
    editable    boolean NOT NULL DEFAULT true,                          -- false = regla fija del Anexo 3
    actualizado_por text,
    actualizado timestamptz NOT NULL DEFAULT now()
);

-- Solo ajustes: los festivos de la Ley 51 de 1983 se calculan para cualquier año (reglas/Festivos).
CREATE TABLE par_festivos (
    fecha date PRIMARY KEY,
    motivo text
);

-- Catálogos parametrizables genéricos (Anexo 5, prueba 4: tipos de documento C, T, R, V; tipos de factura 1 Acuerdo, 2 CTC,
-- 3 Tutela, 4 Requisición; y los que pida el comité). Se crean, modifican y eliminan en vivo desde la pantalla.
-- Diferencia: «fuerzas» y «regionales» no pueden ser códigos de catálogo (chocarían con /api/catalogos/fuerzas y /regionales).
CREATE TABLE catalogos (
    codigo      text PRIMARY KEY CHECK (codigo ~ '^[a-z][a-z0-9_]{2,40}$' AND codigo NOT IN ('fuerzas', 'regionales')),
    nombre      text NOT NULL,
    descripcion text,
    patron      text NOT NULL DEFAULT '^[A-Z0-9]{1,10}$',              -- formato del código de cada valor (validación)
    orden       smallint NOT NULL DEFAULT 0
);

CREATE TABLE catalogo_valores (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalogo    text NOT NULL REFERENCES catalogos (codigo) ON DELETE RESTRICT,
    codigo      text NOT NULL CHECK (char_length(codigo) BETWEEN 1 AND 10),
    descripcion text NOT NULL CHECK (char_length(descripcion) BETWEEN 2 AND 120),
    activo      boolean NOT NULL DEFAULT true,
    orden       smallint NOT NULL DEFAULT 0,
    creado      timestamptz NOT NULL DEFAULT now(),
    actualizado timestamptz NOT NULL DEFAULT now(),
    UNIQUE (catalogo, codigo)
);

-- ---------- Permisos del rol de la aplicación (monolito V3:223-235) ----------
GRANT SELECT ON par_departamentos, par_municipios, par_unidades, par_modalidades, par_tipos_visita TO ${rol_app};
GRANT SELECT, INSERT, UPDATE, DELETE ON par_fuerzas, catalogo_valores, par_festivos TO ${rol_app};
GRANT SELECT, INSERT, UPDATE ON par_regionales, par_parametros, catalogos TO ${rol_app};
GRANT USAGE ON SEQUENCE par_regionales_id_seq, catalogo_valores_id_seq TO ${rol_app};
