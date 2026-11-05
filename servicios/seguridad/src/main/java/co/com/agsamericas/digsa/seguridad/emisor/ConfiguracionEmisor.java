package co.com.agsamericas.digsa.seguridad.emisor;

import co.com.agsamericas.digsa.seguridad.identidad.PropiedadesSeguridad;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Seguridad del emisor OIDC (ADR-004, ADR-014). Tres cadenas, más la de recurso de lume-comun, que va de última para todo lo demás
 * ({@code /api/sesion}, {@code /api/cuenta}: JWT del usuario):
 * <ol>
 *   <li>Endpoints OAuth2/OIDC. Sin sesión, una autorización desde el navegador va a {@code /ingreso} (la pantalla Angular, por el
 *       gateway) y la petición queda guardada en la sesión del emisor para devolverla como {@code continuar}. Al emitir el código, la
 *       sesión del emisor se invalida: el siguiente ingreso vuelve a pedir usuario, contraseña y código.</li>
 *   <li>{@code /api/auth/**}: público. El CSRF y el límite de tasa los aplica el gateway (ADR-014); el servicio solo es alcanzable por la
 *       red interna.</li>
 *   <li>Salud y métricas: públicas en el puerto del servicio, que en el despliegue solo se publica en la red interna.</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(PropiedadesSeguridad.class)
class ConfiguracionEmisor {

    /** La pantalla de ingreso de Angular (ADR-014). */
    static final String INGRESO = "/ingreso";

    @Bean
    @Order(1)
    SecurityFilterChain cadenaEmisor(HttpSecurity http, RequestCache peticiones) throws Exception {
        http
                .oauth2AuthorizationServer((OAuth2AuthorizationServerConfigurer emisor) -> {
                    http.securityMatcher(emisor.getEndpointsMatcher());
                    emisor
                            .authorizationEndpoint(a -> a.authorizationResponseHandler(new RespuestaConCodigo()))
                            .oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .requestCache(c -> c.requestCache(peticiones))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(INGRESO), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(r -> r.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cadenaIngreso(HttpSecurity http, SecurityContextRepository contextos) throws Exception {
        http
                .securityMatcher("/api/auth/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                // CSRF del gateway (cookie XSRF-TOKEN + cabecera), no del servicio (ADR-014)
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(c -> c.securityContextRepository(contextos))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain cadenaSalud(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable());
        return http.build();
    }

    /** La sesión del emisor guarda la autorización pendiente; sin parámetro «continue» en la URL guardada. */
    @Bean
    RequestCache peticionesGuardadas() {
        HttpSessionRequestCache c = new HttpSessionRequestCache();
        c.setMatchingRequestParameterName(null);
        return c;
    }

    @Bean
    SecurityContextRepository contextosEmisor() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * BCrypt de costo 12, como el monolito. Los hash sin prefijo (los migrados del monolito, {@code $2a$...}) se verifican con BCrypt. El
     * prefijo {@code {noop}} solo existe para el secreto del cliente en local y pruebas (lo genera infra/generar-env.sh); fuera de local el
     * secreto va como {@code {bcrypt}...}.
     */
    @Bean
    PasswordEncoder codificador() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        @SuppressWarnings("deprecation")
        PasswordEncoder noop = NoOpPasswordEncoder.getInstance();
        DelegatingPasswordEncoder d = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt, "noop", noop));
        d.setDefaultPasswordEncoderForMatches(bcrypt);
        return d;
    }

    /**
     * Respuesta de la autorización: la misma redirección con {@code code} y {@code state} que hace el emisor por defecto, y además invalida
     * la sesión del emisor. Así la cookie {@code LUME_IDP} no sirve para otra autorización: cerrar la sesión del gateway (salir o
     * inactividad) obliga a ingresar de nuevo, como en el monolito.
     */
    static final class RespuestaConCodigo implements AuthenticationSuccessHandler {

        private final RedirectStrategy redireccion = new DefaultRedirectStrategy();

        @Override
        public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication a) throws IOException {
            OAuth2AuthorizationCodeRequestAuthenticationToken t = (OAuth2AuthorizationCodeRequestAuthenticationToken) a;
            UriComponentsBuilder url = UriComponentsBuilder.fromUriString(t.getRedirectUri())
                    .queryParam(OAuth2ParameterNames.CODE, t.getAuthorizationCode().getTokenValue());
            if (StringUtils.hasText(t.getState())) {
                url.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(t.getState(), StandardCharsets.UTF_8));
            }
            HttpSession s = req.getSession(false);
            if (s != null) {
                s.invalidate();
            }
            SecurityContextHolder.clearContext();
            redireccion.sendRedirect(req, res, url.build(true).toUriString());
        }
    }
}
