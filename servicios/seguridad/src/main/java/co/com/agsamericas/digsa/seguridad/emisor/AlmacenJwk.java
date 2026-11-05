package co.com.agsamericas.digsa.seguridad.emisor;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claves de firma de los tokens (RS256) en la tabla {@code jwk}, con rotación (ADR-004; PENDIENTES_I1, bloqueante 2: en I1 vivían en
 * memoria y un reinicio invalidaba todos los tokens y refrescos). La clave privada se guarda cifrada con AES-256-GCM; la clave de cifrado
 * ({@code lume.jwk.clave-cifrado}) viene del entorno (Vault en el cierre) y nunca de la base. Sin ella el servicio no arranca.
 *
 * <p>Firma la clave vigente (la única con {@code retirada} nula). Al rotar, la anterior se retira pero se sigue publicando en la JWKS
 * hasta que vencen los tokens que firmó. Varias réplicas: la rotación toma un bloqueo consultivo y el índice único {@code uq_jwk_vigente}
 * impide dos vigentes; cada réplica relee las claves cada {@link #RELEER}.
 */
@Component
public class AlmacenJwk implements JWKSource<SecurityContext> {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenJwk.class);
    /** Cuánto se sigue publicando una clave retirada: más que la vida de cualquier JWT que firme (access 5 min, id token 30 min). */
    static final Duration PUBLICAR_RETIRADA = Duration.ofHours(24);
    static final Duration RELEER = Duration.ofMinutes(5);
    private static final long BLOQUEO_ROTACION = 0x4C554D454A574BL; // "LUMEJWK"
    private static final int IV = 12;
    private static final int ETIQUETA_BITS = 128;

    private record Cargadas(List<JWK> claves, Instant hasta) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private final SecretKeySpec cifrado;
    private final Duration rotarCada;
    private final SecureRandom azar = new SecureRandom();
    private volatile Cargadas cargadas;

    public AlmacenJwk(JdbcClient jdbc, TransactionTemplate tx, Clock reloj, @Value("${lume.jwk.clave-cifrado:}") String claveCifrado,
                      @Value("${lume.jwk.rotar-cada-dias:30}") int rotarCadaDias) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.reloj = reloj;
        this.cifrado = claveAes(claveCifrado);
        this.rotarCada = Duration.ofDays(Math.max(1, rotarCadaDias));
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext contexto) {
        return selector.select(new com.nimbusds.jose.jwk.JWKSet(claves()));
    }

    /** Las claves vigentes y publicables (la primera es la que firma). Crea la primera si no hay ninguna. */
    List<JWK> claves() {
        Cargadas c = cargadas;
        Instant ahora = reloj.instant();
        if (c == null || ahora.isAfter(c.hasta()) || c.claves().isEmpty()) {
            if (c == null || c.claves().isEmpty()) {
                rotarSiHaceFalta();
            }
            c = new Cargadas(leer(ahora), ahora.plus(RELEER));
            cargadas = c;
        }
        return c.claves();
    }

    /** Crea la clave vigente si no hay, o una nueva si la vigente ya cumplió su periodo. Diario y al arrancar. */
    @Scheduled(cron = "${lume.jwk.rotar-cron:0 0 4 * * *}", zone = "America/Bogota")
    public void rotarSiHaceFalta() {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", BLOQUEO_ROTACION).query(Object.class).optional();
            Instant ahora = reloj.instant();
            var vigente = jdbc.sql("SELECT kid, creada FROM jwk WHERE retirada IS NULL")
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant()}).optional();
            if (vigente.isPresent() && ((Instant) vigente.get()[1]).plus(rotarCada).isAfter(ahora)) {
                return;
            }
            vigente.ifPresent(v -> jdbc.sql("UPDATE jwk SET retirada = :a, publicar_hasta = :p WHERE kid = :k")
                    .param("a", ahora.atOffset(ZoneOffset.UTC)).param("p", ahora.plus(PUBLICAR_RETIRADA).atOffset(ZoneOffset.UTC))
                    .param("k", v[0]).update());
            RSAKey nueva = generar();
            jdbc.sql("INSERT INTO jwk (kid, publica, privada_cifrada, creada) VALUES (:k, :pub, :priv, :c)")
                    .param("k", nueva.getKeyID()).param("pub", nueva.toPublicJWK().toJSONString())
                    .param("priv", cifrar(privada(nueva).getEncoded())).param("c", ahora.atOffset(ZoneOffset.UTC)).update();
            jdbc.sql("DELETE FROM jwk WHERE retirada IS NOT NULL AND publicar_hasta < :a").param("a", ahora.atOffset(ZoneOffset.UTC)).update();
            LOG.info("[jwk] Clave de firma nueva {}{}", nueva.getKeyID(), vigente.map(v -> " (retirada " + v[0] + ")").orElse(""));
        });
        cargadas = null;
    }

    private List<JWK> leer(Instant ahora) {
        List<JWK> r = new ArrayList<>();
        jdbc.sql("""
                SELECT kid, publica, privada_cifrada, retirada FROM jwk
                WHERE retirada IS NULL OR publicar_hasta >= :a ORDER BY (retirada IS NULL) DESC, creada DESC""")
                .param("a", ahora.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> {
                    try {
                        RSAKey publica = RSAKey.parse(rs.getString("publica"));
                        if (rs.getTimestamp("retirada") != null) {
                            return publica; // solo verifica: la privada de una retirada no se descifra
                        }
                        RSAPrivateKey priv = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                                .generatePrivate(new PKCS8EncodedKeySpec(descifrar(rs.getBytes("privada_cifrada"))));
                        return new RSAKey.Builder(publica).privateKey(priv).build();
                    } catch (ParseException | GeneralSecurityException e) {
                        throw new IllegalStateException("[jwk] No se pudo leer la clave " + rs.getString("kid")
                                + " (¿cambió lume.jwk.clave-cifrado?)", e);
                    }
                }).list().forEach(r::add);
        return List.copyOf(r);
    }

    private RSAKey generar() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(3072, azar);
            KeyPair p = g.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) p.getPublic()).privateKey((RSAPrivateKey) p.getPrivate())
                    .keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("[jwk] No se pudo generar la clave de firma", e);
        }
    }

    private static RSAPrivateKey privada(RSAKey k) {
        try {
            return k.toRSAPrivateKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] cifrar(byte[] claro) {
        try {
            byte[] iv = new byte[IV];
            azar.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cifrado, new GCMParameterSpec(ETIQUETA_BITS, iv));
            byte[] cuerpo = c.doFinal(claro);
            return ByteBuffer.allocate(IV + cuerpo.length).put(iv).put(cuerpo).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("[jwk] No se pudo cifrar la clave", e);
        }
    }

    private byte[] descifrar(byte[] guardado) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, cifrado, new GCMParameterSpec(ETIQUETA_BITS, guardado, 0, IV));
        return c.doFinal(guardado, IV, guardado.length - IV);
    }

    /** Falla cerrado: sin una clave AES-256 válida (32 bytes en base64) el servicio no arranca. */
    private static SecretKeySpec claveAes(String base64) {
        byte[] b;
        try {
            b = base64 == null ? new byte[0] : Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            b = new byte[0];
        }
        if (b.length != 32) {
            throw new IllegalStateException("lume.jwk.clave-cifrado (LUME_JWK_CLAVE) debe ser una clave AES-256 en base64 (32 bytes)");
        }
        return new SecretKeySpec(b, "AES");
    }
}
