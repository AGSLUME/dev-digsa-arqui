-- LUME · seguridad · emisor OIDC: autorizaciones, claves de firma y sesión del emisor (LUME_IDP), todo en base para que sobrevivan a
-- reinicios y funcionen con varias réplicas (PENDIENTES_I1, bloqueante 2: en I1 estaban en memoria).

-- ---------- Autorizaciones de Spring Authorization Server 7.1 (JdbcOAuth2AuthorizationService) ----------
-- Esquema oficial (oauth2-authorization-schema.sql) con los ajustes que el propio archivo pide para PostgreSQL: blob → text y
-- timestamp → timestamptz. Los clientes registrados vienen de la configuración (no hay tabla de clientes) y no se usa consentimiento.
CREATE TABLE oauth2_authorization (
    id                            varchar(100) NOT NULL PRIMARY KEY,
    registered_client_id          varchar(100) NOT NULL,
    principal_name                varchar(200) NOT NULL,
    authorization_grant_type      varchar(100) NOT NULL,
    authorized_scopes             varchar(1000) DEFAULT NULL,
    attributes                    text DEFAULT NULL,
    state                         varchar(500) DEFAULT NULL,
    authorization_code_value      text DEFAULT NULL,
    authorization_code_issued_at  timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata   text DEFAULT NULL,
    access_token_value            text DEFAULT NULL,
    access_token_issued_at        timestamptz DEFAULT NULL,
    access_token_expires_at       timestamptz DEFAULT NULL,
    access_token_metadata         text DEFAULT NULL,
    access_token_type             varchar(100) DEFAULT NULL,
    access_token_scopes           varchar(1000) DEFAULT NULL,
    oidc_id_token_value           text DEFAULT NULL,
    oidc_id_token_issued_at       timestamptz DEFAULT NULL,
    oidc_id_token_expires_at      timestamptz DEFAULT NULL,
    oidc_id_token_metadata        text DEFAULT NULL,
    refresh_token_value           text DEFAULT NULL,
    refresh_token_issued_at       timestamptz DEFAULT NULL,
    refresh_token_expires_at      timestamptz DEFAULT NULL,
    refresh_token_metadata        text DEFAULT NULL,
    user_code_value               text DEFAULT NULL,
    user_code_issued_at           timestamptz DEFAULT NULL,
    user_code_expires_at          timestamptz DEFAULT NULL,
    user_code_metadata            text DEFAULT NULL,
    device_code_value             text DEFAULT NULL,
    device_code_issued_at         timestamptz DEFAULT NULL,
    device_code_expires_at        timestamptz DEFAULT NULL,
    device_code_metadata          text DEFAULT NULL
);
-- El servicio busca por el valor de cada token. Índices hash: un JWT con muchas pantallas puede pasar el tope de 2704 bytes de btree.
CREATE INDEX ix_oauth2_auth_state ON oauth2_authorization USING hash (state);
CREATE INDEX ix_oauth2_auth_code ON oauth2_authorization USING hash (authorization_code_value);
CREATE INDEX ix_oauth2_auth_access ON oauth2_authorization USING hash (access_token_value);
CREATE INDEX ix_oauth2_auth_refresh ON oauth2_authorization USING hash (refresh_token_value);
CREATE INDEX ix_oauth2_auth_id_token ON oauth2_authorization USING hash (oidc_id_token_value);
-- Sin estos dos, findByToken sin tipo (OR sobre todas las columnas) haría un recorrido completo (revisión BD, M1).
CREATE INDEX ix_oauth2_auth_user_code ON oauth2_authorization USING hash (user_code_value);
CREATE INDEX ix_oauth2_auth_device_code ON oauth2_authorization USING hash (device_code_value);
-- Revocación al salir o al cerrar la sesión (por usuario) y purga de vencidas.
CREATE INDEX ix_oauth2_auth_principal ON oauth2_authorization (principal_name);
-- Expiración efectiva: una autorización sin refresh token (código abandonado) también se purga (revisión BD, M2).
CREATE INDEX ix_oauth2_auth_vence ON oauth2_authorization
    ((COALESCE(refresh_token_expires_at, access_token_expires_at, authorization_code_expires_at)));

-- ---------- Claves de firma (JWK RS256) con rotación ----------
-- La clave privada va cifrada con AES-GCM. La clave de cifrado viene del entorno (LUME_JWK_CLAVE; Vault en el cierre) y nunca de la base.
-- Firma la vigente más reciente; las retiradas se siguen publicando en la JWKS hasta que vence el último token que firmaron.
CREATE TABLE jwk (
    kid             varchar(64) PRIMARY KEY,
    publica         text NOT NULL,                          -- JWK pública (JSON)
    privada_cifrada bytea NOT NULL,                         -- PKCS#8 cifrado: iv (12 bytes) || texto cifrado || etiqueta
    creada          timestamptz NOT NULL,
    retirada        timestamptz,                            -- deja de firmar
    publicar_hasta  timestamptz                             -- deja de publicarse en la JWKS
);
-- Una sola clave firmando: dos réplicas que arrancan a la vez no generan dos vigentes (además, bloqueo consultivo en la aplicación).
CREATE UNIQUE INDEX uq_jwk_vigente ON jwk ((true)) WHERE retirada IS NULL;

-- ---------- Sesión del emisor (Spring Session JDBC, cookie LUME_IDP) ----------
-- Esquema estándar de Spring Session (igual al V1 del monolito). principal_name permite cerrar la sesión del emisor de un usuario.
CREATE TABLE spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint NOT NULL,
    last_access_time      bigint NOT NULL,
    max_inactive_interval int NOT NULL,
    expiry_time           bigint NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- ---------- Permisos del rol de la aplicación (D8) ----------
GRANT SELECT, INSERT, UPDATE, DELETE ON oauth2_authorization TO ${rol_app};
GRANT SELECT, INSERT, UPDATE, DELETE ON jwk TO ${rol_app};
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO ${rol_app};
