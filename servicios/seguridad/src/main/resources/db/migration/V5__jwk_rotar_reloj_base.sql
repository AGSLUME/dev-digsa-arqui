-- LUME · seguridad · revisión de la integradora sobre S1.7: jwk_rotar ya no recibe tiempos por parámetro. Con la credencial de la
-- aplicación se podía dejar como vigente una fila basura con una fecha que impedía rotar en 30 días (el emisor sin clave para firmar).
-- Ahora la función usa el reloj de la base, y la aplicación rota de inmediato si la vigente no descifra (AlmacenJwk).
DROP FUNCTION jwk_rotar(varchar, text, bytea, timestamptz, timestamptz);

CREATE FUNCTION jwk_rotar(p_kid varchar, p_publica text, p_privada bytea)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = seguridad, pg_temp
AS $$
BEGIN
    -- Las retiradas se siguen publicando 24 h: más que la vida de cualquier JWT que hayan firmado (access 5 min, id token 30 min)
    UPDATE jwk SET retirada = now(), publicar_hasta = now() + interval '24 hours' WHERE retirada IS NULL;
    INSERT INTO jwk (kid, publica, privada_cifrada, creada) VALUES (p_kid, p_publica, p_privada, now());
    DELETE FROM jwk WHERE retirada IS NOT NULL AND publicar_hasta < now();
END
$$;
REVOKE ALL ON FUNCTION jwk_rotar(varchar, text, bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION jwk_rotar(varchar, text, bytea) TO ${rol_app};
