package co.com.agsamericas.digsa.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    @Bean
    SecurityWebFilterChain cadenaGateway(ServerHttpSecurity http, ReactiveClientRegistrationRepository registros,
                                         @Value("${lume.url-segura:false}") boolean urlSegura) {
        var resolutor = new DefaultServerOAuth2AuthorizationRequestResolver(registros,
                new PathPatternParserServerWebExchangeMatcher(INICIO_INGRESO));
        resolutor.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        CookieServerCsrfTokenRepository csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.sameSite("Strict").secure(urlSegura).path("/"));

        http
                .securityMatcher(new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/login", "/oauth2/**", "/.well-known/**", "/userinfo", "/connect/**")))
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/bff/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(o -> o
                        .authorizationRequestResolver(resolutor)
                        .authenticationMatcher(new PathPatternParserServerWebExchangeMatcher(RETORNO_INGRESO)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.logoutUrl("/bff/salir"))
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
        String json = "{\"type\":\"urn:lume:error:" + estado + "\",\"title\":\"" + titulo + "\",\"status\":" + estado
                + ",\"detail\":\"" + mensaje + "\",\"mensaje\":\"" + mensaje + "\""
                + (motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"") + "}";
        DataBuffer b = res.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(b));
    }
}
