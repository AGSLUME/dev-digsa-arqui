package co.com.agsamericas.digsa.comun.seguridad;

import co.com.agsamericas.digsa.comun.errores.Problemas;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Seguridad de un servicio como servidor de recursos (ADR-003): sin sesión, todo exige un JWT válido (firma por JWKS, {@code iss},
 * {@code aud} y vencimiento, con {@code spring.security.oauth2.resourceserver.jwt.*}), salvo salud y métricas, que en el despliegue solo
 * se publican en la red interna. 401 y 403 en RFC 7807 con los textos del monolito.
 *
 * <p>Un servicio que necesite reglas propias declara su {@link SecurityFilterChain} y esta no se aplica.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class ConfiguracionRecurso {

    static final String SIN_SESION = "La sesión expiró. Vuelva a iniciar sesión.";
    static final String SIN_PERMISO = "No tiene permiso para esta acción.";

    @Bean
    @ConditionalOnMissingBean
    ConvertidorJwt convertidorJwt() {
        return new ConvertidorJwt();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain cadenaRecurso(HttpSecurity http, ConvertidorJwt convertidor) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        http
                .csrf(c -> c.disable()) // sin cookies ni sesión: el CSRF lo aplica el gateway frente al navegador
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(convertidor))
                        .authenticationEntryPoint((req, res, ex) ->
                                responder(res, json, Problemas.de(HttpStatus.UNAUTHORIZED, SIN_SESION, Map.of("motivo", "sin_sesion"))))
                        .accessDeniedHandler((req, res, ex) -> responder(res, json, Problemas.de(HttpStatus.FORBIDDEN, SIN_PERMISO))))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                responder(res, json, Problemas.de(HttpStatus.UNAUTHORIZED, SIN_SESION, Map.of("motivo", "sin_sesion"))))
                        .accessDeniedHandler((req, res, ex) -> responder(res, json, Problemas.de(HttpStatus.FORBIDDEN, SIN_PERMISO))));
        return http.build();
    }

    private static void responder(HttpServletResponse res, JsonMapper json, ProblemDetail p) throws IOException {
        res.setStatus(p.getStatus());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), Problemas.comoMapa(p));
    }
}
