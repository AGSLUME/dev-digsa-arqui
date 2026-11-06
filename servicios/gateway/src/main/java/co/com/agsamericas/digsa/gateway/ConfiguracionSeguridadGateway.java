package co.com.agsamericas.digsa.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.HttpStatusReturningServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

/**
 * Seguridad del BFF (ADR-003): el navegador solo tiene la cookie de sesión {@code LUME_SESION} (HttpOnly, SameSite=Strict); los tokens
 * quedan en la sesión (Redis) y el filtro {@code TokenRelay} los pone hacia los servicios. CSRF con cookie {@code XSRF-TOKEN} y cabecera
 * {@code X-XSRF-TOKEN}, igual que el monolito. 401, 403 y 419 en RFC 7807 con los textos del monolito.
 *
 * <p>Las rutas del emisor ({@code /login}, {@code /oauth2/**}, ...) se reenvían a {@code seguridad} sin pasar por esta cadena: tienen su
 * propia seguridad allí, y así no chocan con los endpoints de cliente OAuth2 del gateway, que viven bajo {@code /bff/**}.
 */
@Configuration
@EnableWebFluxSecurity
class ConfiguracionSeguridadGateway {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConfiguracionSeguridadGateway.class);

    static final String SESION = "LUME_SESION";
    static final String INICIO_INGRESO = "/bff/oauth2/authorization/{registrationId}";
    static final String RETORNO_INGRESO = "/bff/login/oauth2/code/{registrationId}";

    /** CSP del monolito ({@code identidad.ConfiguracionSeguridad}): nada de terceros salvo el captcha de Cloudflare Turnstile. */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://challenges.cloudflare.com",
            "frame-src https://challenges.cloudflare.com blob:",
            "connect-src 'self'",
            "img-src 'self' data: blob:",
            "style-src 'self' 'unsafe-inline'",
            "font-src 'self' data:",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    static final String SIN_SESION = "La sesión expiró. Vuelva a iniciar sesión.";
    static final String SIN_PERMISO = "No tiene permiso para esta acción.";
    static final String PAGINA_EXPIRO = "La página expiró. Recárguela e intente de nuevo.";
    static final String INACTIVIDAD = "La sesión se cerró por inactividad. Vuelva a iniciar sesión.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    SecurityWebFilterChain cadenaGateway(ServerHttpSecurity http, ReactiveClientRegistrationRepository registros, ClienteSeguridad seguridad,
                                         @Value("${lume.url-segura:false}") boolean urlSegura) {
        var resolutor = new DefaultServerOAuth2AuthorizationRequestResolver(registros,
                new PathPatternParserServerWebExchangeMatcher(INICIO_INGRESO));
        resolutor.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        CookieServerCsrfTokenRepository csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.sameSite("Strict").secure(urlSegura).path("/"));

        http
                .securityMatcher(new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/oauth2/**", "/.well-known/**", "/userinfo", "/connect/**")))
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Métricas: nunca por el puerto público (ADR-009). En el cierre se leen por el puerto interno de gestión.
                        .pathMatchers("/actuator/**").denyAll()
                        .pathMatchers("/bff/**").permitAll()
                        // El ingreso (ADR-014): público, con el CSRF de esta cadena y el límite de tasa de su ruta
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(o -> o
                        .authorizationRequestResolver(resolutor)
                        // Si el canje del código falla, se registra la causa (sin el código ni tokens) y la SPA vuelve a /ingreso;
                        // /login?error (el valor por defecto) no existe desde ADR-014.
                        .authenticationFailureHandler((ex, err) -> {
                            LOG.warn("[ingreso] El canje del código OIDC falló: {}", err instanceof
                                    org.springframework.security.oauth2.core.OAuth2AuthenticationException o2
                                    ? o2.getError().getErrorCode() + " " + o2.getError().getDescription() : err.getClass().getSimpleName());
                            return new org.springframework.security.web.server.DefaultServerRedirectStrategy()
                                    .sendRedirect(ex.getExchange(), java.net.URI.create("/ingreso"));
                        })
                        .authenticationMatcher(new PathPatternParserServerWebExchangeMatcher(RETORNO_INGRESO))
                        // Sesión nueva → token CSRF nuevo (como el monolito tras el código, duda 17.16): el que circuló antes del ingreso
                        // deja de servir. Luego a la SPA.
                        .authenticationSuccessHandler((ex, a) -> csrf.generateToken(ex.getExchange())
                                .flatMap(t -> csrf.saveToken(ex.getExchange(), t))
                                .then(new org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler("/")
                                        .onAuthenticationSuccess(ex, a))))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                // Salir (ADR-014; bloqueante 1): cierra la sesión en seguridad (revoca el refresh token y la sesión del emisor), invalida la
                // del gateway (con sus tokens) y responde 204, con o sin sesión. POST con CSRF.
                .logout(l -> l
                        .logoutUrl("/bff/salir")
                        .logoutHandler(new DelegatingServerLogoutHandler(
                                (ex, a) -> {
                                    String sid = a == null ? null : FiltroSesionLume.sid(a);
                                    return sid == null ? Mono.empty() : seguridad.cerrar(sid, "salida");
                                },
                                new SecurityContextServerLogoutHandler(),
                                new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler(new HttpStatusReturningServerLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .csrf(c -> c
                        .csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .accessDeniedHandler((ex, e) -> responder(ex.getResponse(), 419, PAGINA_EXPIRO, null)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((ex, err) -> responder(ex.getResponse(), 401, SIN_SESION, "sin_sesion"))
                        .accessDeniedHandler((ex, err) -> responder(ex.getResponse(), 403, SIN_PERMISO, null)))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .frameOptions(f -> f.mode(Mode.DENY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
                        .hsts(s -> s.includeSubdomains(true).maxAge(Duration.ofDays(365)))
                        .permissionsPolicy(p -> p.policy("geolocation=(), microphone=(), camera=(self)")));
        return http.build();
    }

    /** En WebFlux el token CSRF es diferido: suscribirse a él hace que la cookie {@code XSRF-TOKEN} salga en la respuesta. */
    @Bean
    WebFilter publicarTokenCsrf() {
        return (exchange, chain) -> {
            Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
            return (token == null ? Mono.<CsrfToken>empty() : token).then(chain.filter(exchange));
        };
    }

    /** Reloj del gateway (inactividad, límite de tasa); las pruebas lo reemplazan. */
    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    WebSessionIdResolver resolutorSesion(@Value("${lume.url-segura:false}") boolean urlSegura) {
        CookieWebSessionIdResolver r = new CookieWebSessionIdResolver();
        r.setCookieName(SESION);
        r.addCookieInitializer(c -> c.httpOnly(true).sameSite("Strict").secure(urlSegura).path("/"));
        return r;
    }

    static Mono<Void> responder(ServerHttpResponse res, int estado, String mensaje, String motivo) {
        res.setRawStatusCode(estado);
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        HttpStatus s = HttpStatus.resolve(estado);
        String titulo = s == null ? "Page Expired" : s.getReasonPhrase();
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", "urn:lume:error:" + estado);
        cuerpo.put("title", titulo);
        cuerpo.put("status", estado);
        cuerpo.put("detail", mensaje);
        cuerpo.put("mensaje", mensaje);
        if (motivo != null) {
            cuerpo.put("motivo", motivo);
        }
        // Con Jackson: el mensaje puede venir de seguridad y se escapa como JSON
        DataBuffer b = res.bufferFactory().wrap(JSON.writeValueAsString(cuerpo).getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(b));
    }
}
