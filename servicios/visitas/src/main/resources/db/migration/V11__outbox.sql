-- LUME · visitas · outbox y consumo idempotente (ADR-006), según librerias/comun/src/main/resources/lume/eventos/outbox.sql.
-- Lo usan visitas-a (copia de usuarios, eventos de puntos, regionales y fuerzas) y visitas-b (avisos, alertas y eventos de visitas).
-- mensajes_procesados ya existe (V2): se lleva a la forma que espera comun (id, procesado) sin editar V2, ya aplicada.

-- Eventos por publicar, insertados en la MISMA transacción que el cambio de negocio. El despachador los publica en visitas.eventos.
-- El cuerpo lleva el sobre completo, con datos personales cuando el evento los necesita (Ley 1581): nunca se copia a los logs.
CREATE TABLE outbox (
    id        uuid PRIMARY KEY,                           -- id del evento (messageId): la llave de idempotencia del consumidor
    tipo      text NOT NULL CHECK (tipo ~ '^[a-z_]+(\.[a-z_]+)+$' AND char_length(tipo) <= 100),   -- routing key: <agregado>.<hecho>
    agregado  text NOT NULL CHECK (char_length(agregado) BETWEEN 1 AND 100),                         -- p. ej. punto:42
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

-- Solo la purga borra, y solo lo ya publicado: un evento pendiente no se puede suprimir desde la aplicación (plantilla de comun, cierre S2).
CREATE FUNCTION outbox_solo_publicados() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.publicado IS NULL THEN
        RAISE EXCEPTION 'outbox: no se borra un evento sin publicar (%)', OLD.id USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER outbox_borrado BEFORE DELETE ON outbox FOR EACH ROW EXECUTE FUNCTION outbox_solo_publicados();

-- mensajes_procesados: comun inserta (id, procesado). La columna tipo de V2 sobra (y su NOT NULL rompería el INSERT).
ALTER TABLE mensajes_procesados DROP COLUMN tipo;
ALTER TABLE mensajes_procesados ALTER COLUMN procesado DROP DEFAULT;   -- comun siempre la llena con su reloj
-- El índice por procesado (purga) ya lo creó V2 (ix_mensajes_procesados_procesado); los GRANT de V2 (SELECT, INSERT, DELETE) bastan.

-- DELETE: solo la purga de lo ya publicado (retención configurable).
GRANT SELECT, INSERT, DELETE ON outbox TO ${rol_app};
GRANT UPDATE (intentos, siguiente, publicado, error) ON outbox TO ${rol_app};
