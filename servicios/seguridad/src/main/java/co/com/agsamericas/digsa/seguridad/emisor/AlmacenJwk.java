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
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
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
            List<JWK> leidas = leer(ahora);
            if (leidas.stream().noneMatch(JWK::isPrivate)) {
                // Sin clave para firmar (la vigente no descifra): rotar y volver a leer
                rotarSiHaceFalta();
                leidas = leer(ahora);
            }
            c = new Cargadas(leidas, ahora.plus(RELEER));
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
            var vigente = jdbc.sql("SELECT kid, creada, privada_cifrada FROM jwk WHERE retirada IS NULL")
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getBytes(3)}).optional();
            // Una vigente que no descifra (fila basura dejada con la función) no sirve: se rota ya, no a los 30 días (disponibilidad)
            boolean valida = vigente.isPresent() && descifra((String) vigente.get()[0], (byte[]) vigente.get()[2]);
            if (valida && ((Instant) vigente.get()[1]).plus(rotarCada).isAfter(ahora)) {
                return;
            }
            RSAKey nueva = generar();
            // La aplicación no escribe en jwk (V4, V5): la función retira la vigente, inserta la nueva y borra las retiradas vencidas, con
            // los tiempos de la base (no recibe horas por parámetro)
            jdbc.sql("SELECT jwk_rotar(:k, :pub, :priv)")
                    .param("k", nueva.getKeyID()).param("pub", nueva.toPublicJWK().toJSONString())
                    .param("priv", cifrar(nueva.getKeyID(), privada(nueva).getEncoded())).query(Object.class).optional();
            LOG.info("[jwk] Clave de firma nueva {}{}", nueva.getKeyID(), vigente.map(v -> " (retirada " + v[0] + (valida ? "" : ", no descifraba")
                    + ")").orElse(""));
        });
        cargadas = null;
    }

    /**
     * Las claves a publicar (y la vigente, con su privada para firmar). La pública NUNCA se lee de la columna {@code publica}: se deriva de
     * la privada descifrada con la clave del entorno y el {@code kid} como dato autenticado (revisión de la integradora, M2). Una fila que no
     * descifra (plantada sin la clave de cifrado, o con el kid cambiado) se ignora y queda en el log; si es la vigente, el emisor no tiene con
     * qué firmar y no emite tokens (falla cerrado).
     */
    private List<JWK> leer(Instant ahora) {
        List<JWK> r = new ArrayList<>();
        record Fila(String kid, byte[] privada, boolean vigente) {
        }
        List<Fila> filas = jdbc.sql("""
                SELECT kid, privada_cifrada, retirada IS NULL AS vigente FROM jwk
                WHERE retirada IS NULL OR publicar_hasta >= now() ORDER BY (retirada IS NULL) DESC, creada DESC""")
                .query((rs, n) -> new Fila(rs.getString("kid"), rs.getBytes("privada_cifrada"), rs.getBoolean("vigente"))).list();
        for (Fila f : filas) {
            RSAKey clave;
            try {
                clave = desdePrivada(f.kid(), descifrar(f.kid(), f.privada()));
            } catch (GeneralSecurityException | RuntimeException e) {
                // Ni se publica ni firma. Si era la vigente, el emisor queda sin clave para firmar y no emite tokens (falla cerrado)
                LOG.error("[jwk] Clave {}{} ignorada: no descifra con la clave del entorno (¿cambió lume.jwk.clave-cifrado o la fila no es "
                        + "legítima?)", f.kid(), f.vigente() ? " (VIGENTE)" : "");
                continue;
            }
            r.add(f.vigente() ? clave : clave.toPublicJWK());
        }
        return List.copyOf(r);
    }

    /** ¿La privada cifrada descifra con la clave del entorno y su kid? */
    private boolean descifra(String kid, byte[] privada) {
        try {
            desdePrivada(kid, descifrar(kid, privada));
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** La clave RSA completa a partir de la privada PKCS#8: la pública sale del módulo y el exponente público de la privada (CRT). */
    private static RSAKey desdePrivada(String kid, byte[] pkcs8) throws GeneralSecurityException {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        RSAPublicKey pub = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        return new RSAKey.Builder(pub).privateKey(priv).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
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

    /** AES-256-GCM con el {@code kid} como dato autenticado (AAD): la privada cifrada no se puede mover a otro kid. */
    private byte[] cifrar(String kid, byte[] claro) {
        try {
            byte[] iv = new byte[IV];
            azar.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cifrado, new GCMParameterSpec(ETIQUETA_BITS, iv));
            c.updateAAD(kid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] cuerpo = c.doFinal(claro);
            return ByteBuffer.allocate(IV + cuerpo.length).put(iv).put(cuerpo).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("[jwk] No se pudo cifrar la clave", e);
        }
    }

    private byte[] descifrar(String kid, byte[] guardado) throws GeneralSecurityException {
        if (guardado == null || guardado.length <= IV) {
            throw new GeneralSecurityException("cifrado vacío");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, cifrado, new GCMParameterSpec(ETIQUETA_BITS, guardado, 0, IV));
        c.updateAAD(kid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
