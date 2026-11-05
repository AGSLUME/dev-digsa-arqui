package co.com.agsamericas.digsa.seguridad.emisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones;
import co.com.agsamericas.digsa.seguridad.identidad.ServicioSesiones;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import co.com.agsamericas.digsa.seguridad.soporte.Navegador;
import co.com.agsamericas.digsa.seguridad.soporte.PruebaIntegracion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S1.4: el emisor de punta a punta con el ingreso de ADR-014 (autorización → /ingreso → código → continuar → token), los claims de
 * ADR-004 desde las tablas, la audiencia por destinatario y la revocación inmediata también del refresco (bloqueantes 2 y 3 de
 * PENDIENTES_I1). Claves de firma y autorizaciones en base.
 */
class TokensTest extends PruebaIntegracion {

    private static final String RETORNO = "http://localhost:9000/bff/login/oauth2/code/lume";
    /** Par PKCE del RFC 7636 (apéndice B): no es un secreto. */
    private static final String VERIFICADOR = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String DESAFIO = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String AUTORIZAR = "/oauth2/authorize?response_type=code&client_id=gateway&scope=openid&state=xyz"
            + "&redirect_uri=" + RETORNO + "&code_challenge=" + DESAFIO + "&code_challenge_method=S256";
    private static final String BASICO = "Basic " + Base64.getEncoder()
            .encodeToString("gateway:gateway-solo-prueba".getBytes(StandardCharsets.UTF_8));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ServicioSesiones cierres;
    @Autowired
    AlmacenJwk almacen;

    record Tokens(String acceso, String refresco, String id) {
    }

    @Test
    @DisplayName("ADR-014: authorize → /ingreso → código → continuar → code → tokens con los claims de ADR-004")
    void elIngresoTerminaEnTokensConLosClaimsDeLume() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        Tokens t = ingresarYCanjear(u);

        Map<String, Object> c = claims(t.acceso());
        UUID sid = UUID.fromString((String) c.get("sid"));
        assertThat(c.get("sub")).isEqualTo(String.valueOf(u.id()));
        assertThat(((Number) c.get("uid")).longValue()).isEqualTo(u.id());
        assertThat(c.get("usuario")).isEqualTo(u.usuario());
        assertThat(c.get("nombre")).isEqualTo(u.nombre());
        assertThat(c.get("perfiles")).isEqualTo(List.of("COORDINADOR"));
        assertThat(lista(c, "pantallas")).contains("INICIO", "AGENDA").doesNotContain("USUARIOS", "LOGS");
        assertThat(lista(c, "asignados")).containsExactly((Object) Perfil.COORDINADOR.id());
        assertThat(c.get("alcance")).isEqualTo(Map.of("tipo", "todo"));
        assertThat(c.get("iss")).isEqualTo("http://localhost:9000");
        // Audiencia por destinatario (bloqueante 3): el gateway, los servicios y el monolito
        assertThat(lista(c, "aud")).contains("gateway", "seguridad", "visitas", "reportes", "notificaciones", "monolito");
        assertThat(jdbc.sql("SELECT cerrada IS NULL FROM sesiones WHERE id = :id").param("id", sid).query(Boolean.class).single()).isTrue();
        // El id token también lleva el sid (para cerrar la sesión correcta al salir)
        assertThat(claims(t.id()).get("sid")).isEqualTo(sid.toString());
    }

    @Test
    @DisplayName("ADR-004: el Analista es consulta nacional (alcance todo) y sin pantallas de administración; perfiles solo directos")
    void elAnalistaEsConsultaNacional() throws Exception {
        var u = datos.usuario(Perfil.ANALISTA);
        Map<String, Object> c = claims(ingresarYCanjear(u).acceso());
        assertThat(c.get("alcance")).isEqualTo(Map.of("tipo", "todo"));
        assertThat(c.get("perfiles")).isEqualTo(List.of("ANALISTA"));
        assertThat(lista(c, "pantallas")).contains("ANALITICA", "REPORTES").doesNotContain("AGENDA", "SOLICITUDES", "USUARIOS");
    }

    @Test
    @DisplayName("Bloqueante 2: el refresco funciona con la sesión abierta y falla (invalid_grant, no 500) al cerrarla")
    void elRefrescoSeRevocaAlCerrarLaSesion() throws Exception {
        var u = datos.usuario();
        Tokens t = ingresarYCanjear(u);
        Tokens refrescado = refrescar(t.refresco(), 200);
        assertThat(claims(refrescado.acceso()).get("sid")).isEqualTo(claims(t.acceso()).get("sid"));

        cierres.cerrar(UUID.fromString((String) claims(t.acceso()).get("sid")), RepositorioSesiones.Motivo.SALIDA);
        MvcResult r = mvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, BASICO)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=refresh_token&refresh_token=" + t.refresco()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(t.refresco());
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name = :p")
                .param("p", claims(t.acceso()).get("sid")).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("Bloqueante 2: un usuario inactivado no puede refrescar aunque su sesión siga abierta")
    void unUsuarioInactivadoNoRefresca() throws Exception {
        var u = datos.usuario();
        Tokens t = ingresarYCanjear(u);
        jdbc.sql("UPDATE usuarios SET activo = false WHERE id = :id").param("id", u.id()).update();
        refrescar(t.refresco(), 400);
    }

    @Test
    @DisplayName("Bloqueante 2: las claves de firma están en base, cifradas, y otra instancia (un reinicio) firma con la misma")
    void lasClavesDeFirmaSobrevivenAUnReinicio() throws Exception {
        var u = datos.usuario();
        String kid = encabezado(ingresarYCanjear(u).acceso()).get("kid").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM jwk WHERE kid = :k AND retirada IS NULL").param("k", kid).query(Long.class).single())
                .isEqualTo(1L);
        byte[] guardada = jdbc.sql("SELECT privada_cifrada FROM jwk WHERE kid = :k").param("k", kid).query(byte[].class).single();
        assertThat(new String(guardada, StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE");
        // Una instancia nueva del almacén (como otra réplica o un reinicio) lee la misma clave vigente
        AlmacenJwk otra = new AlmacenJwk(jdbc, contexto.getBean(org.springframework.transaction.support.TransactionTemplate.class), reloj,
                "cHJ1ZWJhLXNvbG8tcHJ1ZWJhLXNvbG8tcHJ1ZWJhLTE=", 30);
        assertThat(otra.claves().get(0).getKeyID()).isEqualTo(kid);
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kid").value(kid))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    @DisplayName("Rotación: la clave nueva firma y la retirada se sigue publicando para validar los tokens que firmó")
    void laRotacionPublicaLaClaveRetirada() throws Exception {
        var u = datos.usuario();
        String antes = encabezado(ingresarYCanjear(u).acceso()).get("kid").asString();
        // La aplicación no escribe en jwk (V4): la clave vigente «envejece» adelantando el reloj
        envejecerVigente();
        almacen.rotarSiHaceFalta();
        var otro = datos.usuario();
        String despues = encabezado(ingresarYCanjear(otro).acceso()).get("kid").asString();
        assertThat(despues).isNotEqualTo(antes);
        String jwks = mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
        assertThat(jwks).contains(antes, despues);
    }

    @Test
    @DisplayName("M3: la sesión del emisor se guarda en JSON (sin serialización de Java, que leída podría ejecutar código)")
    void laSesionDelEmisorSeGuardaEnJson() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        n.navegar(AUTORIZAR).andExpect(status().isFound());
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        java.util.List<byte[]> atributos = jdbc.sql("SELECT attribute_bytes FROM spring_session_attributes").query(byte[].class).list();
        assertThat(atributos).isNotEmpty();
        assertThat(atributos).allSatisfy(b -> {
            assertThat(b.length > 1 && (b[0] & 0xFF) == 0xAC && (b[1] & 0xFF) == 0xED).as("marca de serialización de Java").isFalse();
            assertThat(new String(b, StandardCharsets.UTF_8).strip()).matches("(?s)^[\\[{\"0-9-].*");
        });
    }

    @Test
    @DisplayName("M2: una clave plantada en jwk (sin la clave de cifrado del entorno) ni se publica ni firma")
    void unaClavePlantadaNoSePublica() throws Exception {
        var u = datos.usuario();
        String legitima = encabezado(ingresarYCanjear(u).acceso()).get("kid").asString();
        // El atacante (con la credencial de la aplicación) usa la función de rotación con su propia pública y un cifrado inventado
        var ajena = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("plantada").generate();
        var ahora = reloj.instant().atOffset(java.time.ZoneOffset.UTC);
        jdbc.sql("SELECT jwk_rotar('plantada', :pub, :priv, :a, :h)").param("pub", ajena.toPublicJWK().toJSONString())
                .param("priv", new byte[64]).param("a", ahora).param("h", ahora.plusHours(24)).query(Object.class).optional();
        AlmacenJwk otro = new AlmacenJwk(jdbc, contexto.getBean(org.springframework.transaction.support.TransactionTemplate.class), reloj,
                "cHJ1ZWJhLXNvbG8tcHJ1ZWJhLXNvbG8tcHJ1ZWJhLTE=", 30);
        var publicadas = otro.claves();
        assertThat(publicadas).extracting(com.nimbusds.jose.jwk.JWK::getKeyID).doesNotContain("plantada").contains(legitima);
        assertThat(publicadas).noneMatch(com.nimbusds.jose.jwk.JWK::isPrivate); // la vigente es la plantada: nadie firma (falla cerrado)
        // Deja el almacén como estaba para las demás pruebas: una clave vigente legítima
        envejecerVigente();
        almacen.rotarSiHaceFalta();
    }

    /** Adelanta el reloj hasta 31 días después de la creación de la clave vigente (rotación sin escribir en jwk). */
    private void envejecerVigente() {
        java.time.Instant creada = jdbc.sql("SELECT creada FROM jwk WHERE retirada IS NULL").query(java.sql.Timestamp.class).single()
                .toInstant();
        java.time.Duration falta = java.time.Duration.between(reloj.instant(), creada.plus(java.time.Duration.ofDays(31)));
        if (!falta.isNegative()) {
            reloj.avanzar(falta);
        }
    }

    @Test
    @DisplayName("Bloqueante 3: /api/** de seguridad exige un token con audiencia seguridad (uno de máquina para hola no sirve)")
    void elServicioExigeSuAudiencia() throws Exception {
        mvc.perform(post("/api/cuenta/clave").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().jwt(j -> j.claim("uid", 1L).audience(List.of("hola")))))
                .andExpect(status().is4xxClientError());
        // Un token real sin la audiencia: el decodificador del servicio lo rechaza
        String maquina = tokenDeMaquinaHola();
        mvc.perform(post("/api/cuenta/clave").header(HttpHeaders.AUTHORIZATION, "Bearer " + maquina)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actual\":\"x\",\"clave\":\"y\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.motivo").value("sin_sesion"));
    }

    @Test
    @DisplayName("ADR-014: la sesión del emisor usa la cookie LUME_IDP, HttpOnly y SameSite=Lax (la única que el gateway deja pasar)")
    void laCookieDelEmisorEsLumeIdp() throws Exception {
        var r = navegador().navegar(AUTORIZAR).andExpect(status().isFound()).andReturn().getResponse();
        assertThat(r.getHeaders("Set-Cookie")).anySatisfy(c -> assertThat(c).startsWith("LUME_IDP=").contains("HttpOnly")
                .contains("SameSite=Lax"));
        assertThat(r.getHeaders("Set-Cookie")).noneSatisfy(c -> assertThat(c).startsWith("SESSION="));
    }

    @Test
    @DisplayName("El código de autorización no se emite sin PKCE ni con otro redirect_uri")
    void elEmisorExigePkceYRedirectRegistrado() throws Exception {
        Navegador n = navegador();
        // Sin PKCE: el error vuelve al cliente registrado (RFC 6749 4.1.2.1), nunca con un código
        String sinPkce = n.navegar(AUTORIZAR.replace("&code_challenge=" + DESAFIO + "&code_challenge_method=S256", ""))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(sinPkce).startsWith(RETORNO).contains("error=invalid_request").doesNotContain("code=");
        // Con un redirect_uri no registrado: no se redirige a ningún lado
        n.navegar(AUTORIZAR.replace(RETORNO, "https://otro.sitio.example/cb")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("B6: tras un ingreso completo y el cierre de la sesión (inactividad), una autorización nueva con el mismo navegador "
            + "vuelve a /ingreso y nunca emite un código sin clave y MFA (equipo compartido)")
    void laSesionDelEmisorNoSobreviveALaDeLume() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        Tokens t = ingresarYCanjear(u, n);
        // 1) Aun con la sesión de LUME abierta, la cookie LUME_IDP ya no autoriza: se invalidó al emitir el código
        String inmediata = n.navegar(AUTORIZAR).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(inmediata).endsWith("/ingreso").doesNotContain("code=");
        // 2) El gateway cierra por inactividad: tampoco hay código, y el refresh token deja de servir
        contexto.getBean(co.com.agsamericas.digsa.seguridad.identidad.ServicioEstadoSesion.class)
                .cerrarPorGateway(UUID.fromString((String) claims(t.acceso()).get("sid")), RepositorioSesiones.Motivo.INACTIVIDAD);
        String despues = n.navegar(AUTORIZAR).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(despues).endsWith("/ingreso").doesNotContain("code=");
        refrescar(t.refresco(), 400);
        // 3) Volver a entrar exige otra vez usuario, contraseña y un código nuevo
        int antes = correos.a(u.correo()).size();
        ingresarYCanjear(u, n);
        assertThat(correos.a(u.correo())).hasSize(antes + 1);
    }

    @Test
    @DisplayName("B6/M1: MFA correcto y nadie autoriza en 60 s (pestaña cerrada) → la autorización vuelve a /ingreso, sin código")
    void laSesionAutenticadaVenceSiNoSeAutorizaEnSesentaSegundos() throws Exception {
        var u = datos.usuario();
        Navegador n = navegador();
        n.navegar(AUTORIZAR).andExpect(status().isFound());
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        String continuar = Navegador.campo(n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(u.correo())))
                .andExpect(status().isOk()), "continuar");
        reloj.avanzar(java.time.Duration.ofSeconds(61));
        String ubicacion = n.navegar(continuar).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(ubicacion).endsWith("/ingreso").doesNotContain("code=");
    }

    @Test
    @DisplayName("B6/M1: A supera el MFA y no autoriza; B ingresa en el mismo navegador → el contexto de A desaparece; el código es de B")
    void unIngresoNuevoDescartaElContextoAnterior() throws Exception {
        var a = datos.usuario();
        var b = datos.usuario();
        Navegador n = navegador();
        n.navegar(AUTORIZAR).andExpect(status().isFound());
        n.post("/api/auth/ingreso", Map.of("usuario", a.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        String continuarA = Navegador.campo(n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(a.correo())))
                .andExpect(status().isOk()), "continuar");
        // B empieza su ingreso en el mismo navegador: la sesión del emisor de A se descarta
        n.post("/api/auth/ingreso", Map.of("usuario", b.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        String sinCodigo = n.navegar(continuarA).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(sinCodigo).endsWith("/ingreso").doesNotContain("code=");
        // B termina su ingreso: el código que se emite es de B
        n.post("/api/auth/ingreso", Map.of("usuario", b.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        String continuarB = Navegador.campo(n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(b.correo())))
                .andExpect(status().isOk()), "continuar");
        String conCodigo = n.navegar(continuarB).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        var q = UriComponentsBuilder.fromUri(URI.create(conCodigo)).build().getQueryParams();
        String cuerpo = mvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, BASICO)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&code=" + q.getFirst("code") + "&redirect_uri=" + RETORNO
                                + "&code_verifier=" + VERIFICADOR))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(claims(JSON.readTree(cuerpo).get("access_token").asString()).get("sub")).isEqualTo(String.valueOf(b.id()));
    }

    // ---------- auxiliares ----------

    private Tokens ingresarYCanjear(DatosPrueba.Ficticio u) throws Exception {
        return ingresarYCanjear(u, navegador());
    }

    private Tokens ingresarYCanjear(DatosPrueba.Ficticio u, Navegador n) throws Exception {
        n.navegar(AUTORIZAR).andExpect(status().isFound());
        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", DatosPrueba.CLAVE)).andExpect(status().isOk());
        String continuar = Navegador.campo(n.post("/api/auth/codigo", Map.of("codigo", correos.ultimoCodigo(u.correo())))
                .andExpect(status().isOk()), "continuar");
        String ubicacion = n.navegar(continuar).andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(ubicacion).startsWith(RETORNO);
        var q = UriComponentsBuilder.fromUri(URI.create(ubicacion)).build().getQueryParams();
        assertThat(q.getFirst("state")).isEqualTo("xyz");
        String cuerpo = mvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, BASICO)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&code=" + q.getFirst("code") + "&redirect_uri=" + RETORNO
                                + "&code_verifier=" + VERIFICADOR))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode j = JSON.readTree(cuerpo);
        return new Tokens(j.get("access_token").asString(), j.get("refresh_token").asString(), j.get("id_token").asString());
    }

    private Tokens refrescar(String refresco, int esperado) throws Exception {
        String cuerpo = mvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, BASICO)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=refresh_token&refresh_token=" + refresco))
                .andExpect(status().is(esperado)).andReturn().getResponse().getContentAsString();
        if (esperado != 200) {
            return null;
        }
        JsonNode j = JSON.readTree(cuerpo);
        return new Tokens(j.get("access_token").asString(), j.get("refresh_token").asString(), null);
    }

    /** Token de máquina para hola, emitido por el cliente gateway con client_credentials no permitido: se usa el del perfil local. */
    private String tokenDeMaquinaHola() throws Exception {
        // El cliente gateway no tiene client_credentials: se fabrica un JWT firmado con la clave vigente y audiencia hola
        var claves = almacen.claves();
        var rsa = (com.nimbusds.jose.jwk.RSAKey) claves.get(0);
        var firmador = new com.nimbusds.jose.crypto.RSASSASigner(rsa);
        var jws = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(rsa.getKeyID()).build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("http://localhost:9000").subject("humo").audience("hola")
                        .claim("uid", 1L).expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000)).build());
        jws.sign(firmador);
        return jws.serialize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claims(String jwt) {
        return JSON.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lista(Map<String, Object> c, String claim) {
        return (List<Object>) c.get(claim);
    }

    private static JsonNode encabezado(String jwt) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
    }
}
