-- LUME · visitas · la visita, su equipo, reparto, historial y notificación previa (VISITAS A; esquema compartido con VISITAS B).
-- Portado de digsa-java (develop @ 1ff82de): V6__agenda (tablas), V8__ajustes_revision_s2 (número, orden del equipo, notificación con 50
-- destinatarios y estado «parcial») y V13__seguimiento (origen_id y seguimiento_n de la cadena de seguimientos). Mismos nombres (ADR-005).
-- La tabla visitas lleva TODAS sus columnas, también las que escribe ejecución (VISITAS B): reaperturas, checklist_version, iniciada,
-- origen_id y seguimiento_n. Ejecución la cambia solo por la interfaz interna de agenda (bloquear, cambiarEstado, reabrir).
-- Diferencias con el monolito:
--   * Sin FK hacia usuarios (auditor_id, creado_por, usuario_id): los usuarios son de «seguridad» y aquí son copia (usuarios_copia, V2).
--   * visitas_notificaciones conserva destinatarios, asunto y cuerpo sin plazo, como el monolito (decisión 7.5): es evidencia de la
--     visita y cumple el año exigido para los correos (decisión D1).

CREATE TABLE visitas (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero            text UNIQUE,                                     -- VS-AAAAMMDD-NNNN; se asigna en la misma transacción
    tipo              text NOT NULL REFERENCES par_tipos_visita (codigo) ON DELETE RESTRICT,
    punto_id          bigint NOT NULL REFERENCES puntos (id) ON DELETE RESTRICT,
    fecha             date NOT NULL,
    hora              time,
    auditor_id        bigint NOT NULL,                                 -- líder (uid de usuarios_copia)
    alcance           text NOT NULL CHECK (char_length(alcance) BETWEEN 1 AND 5000),
    estado            text NOT NULL DEFAULT 'programada'
                      CHECK (estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion', 'cerrada', 'cancelada')),
    fecha_solicitud   date,                                            -- visita a necesidad: fecha del escrito (Anexo 3 p.14)
    checklist_version text REFERENCES chk_versiones (version) ON DELETE RESTRICT,   -- se fija al iniciar
    equipo_sin_notificar boolean NOT NULL DEFAULT false,               -- cambió el equipo de una visita ya notificada
    iniciada          timestamptz,
    reaperturas       smallint NOT NULL DEFAULT 0,
    creado_por        bigint NOT NULL,                                 -- uid de usuarios_copia
    creado            timestamptz NOT NULL DEFAULT now(),
    actualizado       timestamptz NOT NULL DEFAULT now(),
    origen_id         bigint REFERENCES visitas (id) ON DELETE RESTRICT,   -- seguimiento al plan: la visita que verifica (V13)
    seguimiento_n     smallint CHECK (seguimiento_n >= 1),
    CHECK (tipo <> 'necesidad' OR fecha_solicitud IS NOT NULL),
    CONSTRAINT ck_visitas_numero CHECK (numero IS NULL OR numero ~ '^VS-[0-9]{8}-[0-9]{4,}$'),
    CONSTRAINT visitas_seguimiento_check CHECK ((origen_id IS NULL) = (seguimiento_n IS NULL)),
    CONSTRAINT visitas_origen_distinto CHECK (origen_id IS NULL OR origen_id <> id),
    CONSTRAINT visitas_seguimiento_tipo CHECK (origen_id IS NULL OR tipo = 'plan')
);
CREATE INDEX ix_visitas_fecha ON visitas (fecha, id);
CREATE INDEX ix_visitas_punto ON visitas (punto_id, fecha);
CREATE INDEX ix_visitas_auditor ON visitas (auditor_id, fecha);
CREATE INDEX ix_visitas_estado ON visitas (estado) WHERE estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion');
CREATE INDEX ix_visitas_creado_por ON visitas (creado_por);
-- Un solo seguimiento vigente por visita de origen
CREATE UNIQUE INDEX ux_visitas_origen_vigente ON visitas (origen_id) WHERE origen_id IS NOT NULL AND estado <> 'cancelada';
CREATE INDEX ix_visitas_origen ON visitas (origen_id) WHERE origen_id IS NOT NULL;

-- Componentes que el coordinador eligió para la visita (código del componente; vale para cualquier versión del checklist)
CREATE TABLE visitas_componentes (
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    componente text NOT NULL CHECK (componente ~ '^C[0-9]{2}$'),
    PRIMARY KEY (visita_id, componente)
);

-- Equipo: un líder (también en visitas.auditor_id) y de 0 a 5 de apoyo, cada uno con su orden (el líder con el 0)
CREATE TABLE visitas_auditores (
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    usuario_id bigint NOT NULL,                                        -- uid de usuarios_copia
    funcion    text NOT NULL CHECK (funcion IN ('lider', 'apoyo')),
    orden      smallint NOT NULL CHECK (orden BETWEEN 0 AND 5),
    PRIMARY KEY (visita_id, usuario_id),
    CONSTRAINT uq_visitas_auditores_orden UNIQUE (visita_id, orden),
    CONSTRAINT ck_visitas_auditores_lider_orden CHECK ((funcion = 'lider') = (orden = 0))
);
CREATE UNIQUE INDEX uq_visitas_auditores_lider ON visitas_auditores (visita_id) WHERE funcion = 'lider';
CREATE INDEX ix_visitas_auditores_usuario ON visitas_auditores (usuario_id, visita_id);

-- Reparto: cada parte (componente, OBL o SIS) tiene exactamente un dueño, que es del equipo
CREATE TABLE visitas_reparto (
    visita_id     bigint NOT NULL,
    parte         text NOT NULL CHECK (parte ~ '^(C[0-9]{2}|OBL|SIS)$'),
    usuario_id    bigint NOT NULL,
    asignado_por  text NOT NULL,                                     -- "sistema" o el usuario del coordinador
    asignado      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (visita_id, parte),
    FOREIGN KEY (visita_id, usuario_id) REFERENCES visitas_auditores (visita_id, usuario_id) ON DELETE RESTRICT
);
CREATE INDEX ix_visitas_reparto_usuario ON visitas_reparto (visita_id, usuario_id);

-- Historial de la visita: evidencia, no se borra ni se modifica. Lo escriben agenda, ejecución, OPL y seguimiento.
CREATE TABLE visitas_historial (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id  bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    fecha      timestamptz NOT NULL DEFAULT now(),
    usuario_id bigint,                                               -- NULL = el sistema
    usuario    text,
    evento     text NOT NULL CHECK (char_length(evento) <= 60),
    detalle    text CHECK (char_length(detalle) <= 1000)
);
CREATE INDEX ix_visitas_historial_visita ON visitas_historial (visita_id, id);
CREATE INDEX ix_visitas_historial_usuario ON visitas_historial (usuario_id);

-- Notificación previa: se registra antes de enviar (Anexo 3 p.9). Tope de 50 en la calculada; el de 15 de la manual está en el código.
CREATE TABLE visitas_notificaciones (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visita_id      bigint NOT NULL REFERENCES visitas (id) ON DELETE RESTRICT,
    destinatarios  text[] NOT NULL CHECK (cardinality(destinatarios) BETWEEN 1 AND 50),
    asunto         text NOT NULL CHECK (char_length(asunto) <= 255),
    cuerpo         text NOT NULL,
    origen         text NOT NULL,
    estado         text NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'enviada', 'parcial', 'fallida')),
    error          text CHECK (char_length(error) <= 500),
    usuario_id     bigint,
    creado         timestamptz NOT NULL DEFAULT now(),
    resultado      timestamptz
);
CREATE INDEX ix_visitas_notificaciones_visita ON visitas_notificaciones (visita_id, id);
CREATE INDEX ix_visitas_notificaciones_usuario ON visitas_notificaciones (usuario_id);

-- ---------- Permisos del rol de la aplicación (monolito V6:100-110): las visitas no se borran ----------
GRANT SELECT, INSERT, UPDATE ON visitas, visitas_notificaciones TO ${rol_app};
GRANT SELECT, INSERT ON visitas_historial TO ${rol_app};
GRANT SELECT, INSERT, UPDATE, DELETE ON visitas_componentes, visitas_auditores, visitas_reparto TO ${rol_app};
GRANT USAGE ON SEQUENCE visitas_id_seq, visitas_historial_id_seq, visitas_notificaciones_id_seq TO ${rol_app};
