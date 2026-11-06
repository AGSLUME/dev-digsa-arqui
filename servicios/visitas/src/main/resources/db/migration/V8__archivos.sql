-- LUME · visitas · archivos de la visita (evidencias de los ítems, de la visita, de las obligaciones y el PDF del acta).
-- La crea VISITAS A en VA1 para que VISITAS B la use desde el primer día (plan de B §3); la escriben ejecución y OPL.
-- Portado de digsa-java (develop @ 1ff82de): V9__ejecucion_y_acta (tabla) con el CHECK de clave ampliado en V12__flujo_opl (ob:OB-...).
-- Los archivos viven en el almacén (Almacen de librerias/comun, ADR-005) con nombre aleatorio y su SHA-256; quitar uno lo marca
-- eliminado sin borrarlo (spec archivos).
-- Diferencia con el monolito: subido_por sin FK (uid de usuarios_copia; los usuarios son de «seguridad»).

CREATE TABLE archivos (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id       bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    clave           text NOT NULL CHECK (clave ~ '^(visita|acta|it:[A-Z0-9][A-Z0-9-]{1,15}|ob:OB-[A-Z0-9-]{1,20})$'),
    nombre_original text NOT NULL CHECK (char_length(nombre_original) BETWEEN 1 AND 200),
    ruta            text NOT NULL UNIQUE CHECK (ruta ~ '^[A-Za-z0-9_/-]+\.[a-z0-9]+$'),
    tipo            text NOT NULL,
    tamano          bigint NOT NULL CHECK (tamano > 0),
    sha256          char(64) NOT NULL,
    subido_por      bigint,
    subido          timestamptz NOT NULL DEFAULT now(),
    eliminado       timestamptz
);
CREATE INDEX ix_archivos_visita ON archivos (visita_id, clave) WHERE eliminado IS NULL;
CREATE INDEX ix_archivos_usuario ON archivos (subido_por);

-- Permisos del rol de la aplicación (monolito V9:116): sin DELETE (se marca eliminado).
GRANT SELECT, INSERT, UPDATE ON archivos TO ${rol_app};
GRANT USAGE ON SEQUENCE archivos_id_seq TO ${rol_app};
