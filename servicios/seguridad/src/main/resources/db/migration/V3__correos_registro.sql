-- LUME · seguridad · registro de correos del adaptador PROVISIONAL (D2 de la integradora, 2026-11-05).
-- Mientras no exista «notificaciones» (R1, oleada 2), seguridad envía el código MFA y los enlaces por SMTP y deja aquí el registro y el
-- contenido de cada correo con el secreto ENMASCARADO (el código o el enlace nunca se guardan en claro). Se purga al cumplir 1 año
-- (interventoría: registro y contenido 1 año). Cuando llegue «notificaciones», esta tabla y su adaptador se retiran.
CREATE TABLE correos_registro (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    creado        timestamptz NOT NULL,
    plantilla     text NOT NULL CHECK (plantilla ~ '^[a-z_]{3,40}$'),
    usuario_id    bigint,                                   -- sin FK: el registro sobrevive aunque cambie el usuario
    destinatario  text NOT NULL CHECK (char_length(destinatario) <= 150),
    asunto        text NOT NULL CHECK (char_length(asunto) <= 200),
    cuerpo        text NOT NULL,                            -- HTML con el secreto enmascarado
    estado        text NOT NULL CHECK (estado IN ('enviado', 'fallido')),
    error         text                                      -- la aplicación lo corta a 500: un CHECK aquí ocultaría el fallo original
);
CREATE INDEX ix_correos_registro_creado ON correos_registro (creado);

-- Permisos del rol de la aplicación (D8): registra y purga; nunca modifica un registro.
GRANT SELECT, INSERT, DELETE ON correos_registro TO ${rol_app};
GRANT USAGE ON SEQUENCE correos_registro_id_seq TO ${rol_app};
