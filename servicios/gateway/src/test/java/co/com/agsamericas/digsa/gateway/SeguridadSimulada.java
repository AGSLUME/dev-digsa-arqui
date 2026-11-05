package co.com.agsamericas.digsa.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Doble de {@code seguridad} para las pruebas del gateway (Reactor Netty, sin dependencias nuevas): emite tokens firmados con una clave de
 * prueba, publica su JWKS, responde {@code /interno/sesiones/**} según lo que fije la prueba y hace eco de {@code /api/auth/**} y
 * {@code /api/sesion} (cabeceras recibidas). Todo ficticio.
 */
final class SeguridadSimulada implements AutoCloseable {

    static final String SID = "6f9b1c1e-0000-4000-8000-000000000001";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RSAKey clave;
    private final DisposableServer servidor;

    /** Lo que recibió (para las aserciones). */
    final List<String> cierres = new CopyOnWriteArrayList<>();
    final List<String> actividades = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> recibidas = new CopyOnWriteArrayList<>();

    /** Lo que responde (lo fija cada prueba). */
    volatile String nonce;
    volatile long accesoVenceEnSegundos = 300;
    volatile boolean refrescoFalla;
    volatile Map<String, Object> estado = vigente(10);

    SeguridadSimulada() {
        try {
            clave = new RSAKeyGenerator(2048).keyID("prueba").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        servidor = HttpServer.create().host("127.0.0.1").port(0).route(r -> r
                .post("/oauth2/token", this::token)
                .get("/oauth2/jwks", (req, res) -> json(res, 200, new JWKSet(clave.toPublicJWK()).toJSONObject()))
                .get("/userinfo", (req, res) -> json(res, 200, Map.of("sub", "42")))
                .get("/interno/sesiones/{sid}", (req, res) -> json(res, 200, estado))
                .post("/interno/sesiones/{sid}/actividad", (req, res) -> {
                    actividades.add(req.param("sid"));
                    return res.status(204).send();
                })
                .post("/interno/sesiones/{sid}/cerrar", (req, res) -> req.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap(b -> {
                            cierres.add(req.param("sid") + " " + b);
                            return res.status(204).send().then();
                        }))
                .route(req -> req.uri().startsWith("/api/"), this::eco))
                .bindNow();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.port();
    }

    static Map<String, Object> vigente(int minutos) {
        return Map.of("vigente", true, "minutos", minutos, "uid", 42);
    }

    static Map<String, Object> cerrada(String motivo, String mensaje) {
        return Map.of("vigente", false, "minutos", 10, "uid", 42, "motivo", motivo, "mensaje", mensaje);
    }

    private Mono<Void> token(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asString().flatMap(cuerpo -> {
            Map<String, String> p = formulario(cuerpo);
            String tipo = p.get("grant_type");
            if ("client_credentials".equals(tipo)) {
                return json(res, 200, Map.of("access_token", firmar(Map.of("scope", "seguridad:sesiones"), "seguridad", 300),
                        "token_type", "Bearer", "expires_in", 300, "scope", "seguridad:sesiones")).then();
            }
            if ("refresh_token".equals(tipo) && refrescoFalla) {
                return json(res, 400, Map.of("error", "invalid_grant", "error_description", "La sesión ya no está abierta.")).then();
            }
            String acceso = firmar(Map.of("uid", 42, "sid", SID, "scope", "openid profile"), "gateway", accesoVenceEnSegundos);
            String id = firmar(Map.of("sid", SID, "nonce", nonce == null ? "" : nonce), "gateway", 1800);
            return json(res, 200, Map.of("access_token", acceso, "token_type", "Bearer", "expires_in", accesoVenceEnSegundos,
                    "refresh_token", "refresco-ficticio", "id_token", id, "scope", "openid profile")).then();
        });
    }

    private Mono<Void> eco(HttpServerRequest req, HttpServerResponse res) {
        Map<String, String> h = new java.util.HashMap<>();
        h.put("ruta", req.uri());
        h.put("cookie", req.requestHeaders().get("Cookie", ""));
        h.put("authorization", req.requestHeaders().get("Authorization", ""));
        recibidas.add(h);
        res.addHeader("Set-Cookie", "LUME_IDP=del-emisor; Path=/; HttpOnly");
        res.addHeader("Set-Cookie", "INTRUSA=de-un-servicio; Path=/");
        return req.receive().then(json(res, 200, h).then());
    }

    private String firmar(Map<String, Object> claims, String aud, long venceEnSegundos) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer("http://localhost:9000").subject("42").audience(aud)
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(venceEnSegundos)));
            claims.forEach(b::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), b.build());
            jwt.sign(new RSASSASigner(clave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mono<Void> json(HttpServerResponse res, int estado, Object cuerpo) {
        return res.status(estado).header("Content-Type", "application/json").sendString(Mono.just(JSON.writeValueAsString(cuerpo))).then();
    }

    private static Map<String, String> formulario(String cuerpo) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String par : cuerpo.split("&")) {
            int i = par.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    @Override
    public void close() {
        servidor.disposeNow();
    }
}
