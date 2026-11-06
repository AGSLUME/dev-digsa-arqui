-- LUME · seguridad · outbox de eventos (ADR-006): copia de librerias/comun/src/main/resources/lume/eventos/outbox.sql (S2-OUTBOX),
-- más la versión del agregado usuario para los eventos usuario.* (el consumidor descarta las versiones <= a la aplicada).


-- Eventos por publicar, insertados en la MISMA transacción que el cambio de negocio. El despachador los publica en <origen>.eventos.
-- El cuerpo lleva el sobre completo, con datos personales cuando el evento los necesita (Ley 1581): nunca se copia a los logs.
CREATE TABLE outbox (
    id        uuid PRIMARY KEY,                           -- id del evento (messageId): la llave de idempotencia del consumidor
    tipo      text NOT NULL CHECK (tipo ~ '^[a-z_]+(\.[a-z_]+)+$' AND char_length(tipo) <= 100),   -- routing key: <agregado>.<hecho>
    agregado  text NOT NULL CHECK (char_length(agregado) BETWEEN 1 AND 100),                         -- p. ej. usuario:42
    version   bigint NOT NULL,                            -- versión del agregado: el consumidor descarta las <= a la aplicada
    cuerpo    jsonb NOT NULL,                             -- el sobre {id, tipo, version, ocurrido, origen, traza, datos}
    creado    timestamptz NOT NULL,
    intentos  integer NOT NULL DEFAULT 0,
    siguiente timestamptz NOT NULL,                       -- próximo intento (espera creciente si el broker falla)
    publicado timestamptz,                                -- confirmado por el broker
    error     text CHECK (char_length(error) <= 300)      -- último error del envío (sin el cuerpo)
);
CREATE INDEX ix_outbox_pendientes ON outbox (siguiente, creado) WHERE publicado IS NULL;
CREATE INDEX ix_outbox_publicados ON outbox (publicado) WHERE publicado IS NOT NULL;

-- Eventos ya aplicados por los consumidores de este servicio (entrega «al menos una vez»).
CREATE TABLE mensajes_procesados (
    id        uuid PRIMARY KEY,
    procesado timestamptz NOT NULL
);
CREATE INDEX ix_mensajes_procesados ON mensajes_procesados (procesado);

-- DELETE: solo la purga de lo ya publicado o procesado (retención configurable).
GRANT SELECT, INSERT, DELETE ON outbox, mensajes_procesados TO ${rol_app};
GRANT UPDATE (intentos, siguiente, publicado, error) ON outbox TO ${rol_app};

-- Versión del agregado usuario: sube con cada evento usuario.* o cuenta.bloqueada (EventosUsuario). usuarios ya tiene GRANT UPDATE.
ALTER TABLE usuarios ADD COLUMN version bigint NOT NULL DEFAULT 0;
