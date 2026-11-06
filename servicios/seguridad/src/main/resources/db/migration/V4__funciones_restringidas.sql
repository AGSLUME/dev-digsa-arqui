-- LUME · seguridad · revisión de la integradora sobre S1 (M2 y B-d): el rol de la aplicación deja de escribir directamente en dos tablas
-- sensibles. Lo que necesita hacer lo hace por funciones SECURITY DEFINER (corren como el dueño del esquema, seguridad_ddl), que solo
-- permiten esa operación.

-- ---------- Claves de firma (M2) ----------
-- Con INSERT/UPDATE sobre jwk, una inyección SQL o la credencial de la aplicación permitiría plantar una clave y firmar tokens. Ahora:
--   * la aplicación solo lee jwk;
--   * la rotación es esta función (retira la vigente, inserta la nueva, borra las retiradas vencidas);
--   * y además la aplicación solo usa claves cuya privada descifra con la clave del entorno y con el kid como dato autenticado (AES-GCM
--     con AAD): una fila plantada por esta función sin la clave de cifrado no firma ni se publica.
CREATE FUNCTION jwk_rotar(p_kid varchar, p_publica text, p_privada bytea, p_ahora timestamptz, p_publicar_hasta timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = seguridad, pg_temp
AS $$
BEGIN
    UPDATE jwk SET retirada = p_ahora, publicar_hasta = p_publicar_hasta WHERE retirada IS NULL;
    INSERT INTO jwk (kid, publica, privada_cifrada, creada) VALUES (p_kid, p_publica, p_privada, p_ahora);
    DELETE FROM jwk WHERE retirada IS NOT NULL AND publicar_hasta < p_ahora;
END
$$;
REVOKE ALL ON FUNCTION jwk_rotar(varchar, text, bytea, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION jwk_rotar(varchar, text, bytea, timestamptz, timestamptz) TO ${rol_app};
REVOKE INSERT, UPDATE, DELETE ON jwk FROM ${rol_app};

-- ---------- Registro de correos (B-d) ----------
-- Es evidencia de 1 año para la interventoría: la aplicación no puede borrarlo. La purga solo quita lo que tiene más de 366 días según el
-- reloj de la base (no según un parámetro que la aplicación pudiera adelantar), en lotes.
CREATE FUNCTION correos_registro_purgar(p_lote int)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = seguridad, pg_temp
AS $$
DECLARE
    borrados int;
BEGIN
    DELETE FROM correos_registro
    WHERE id IN (SELECT id FROM correos_registro WHERE creado < now() - interval '366 days' ORDER BY id LIMIT greatest(1, least(p_lote, 10000)));
    GET DIAGNOSTICS borrados = ROW_COUNT;
    RETURN borrados;
END
$$;
REVOKE ALL ON FUNCTION correos_registro_purgar(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION correos_registro_purgar(int) TO ${rol_app};
REVOKE DELETE ON correos_registro FROM ${rol_app};
