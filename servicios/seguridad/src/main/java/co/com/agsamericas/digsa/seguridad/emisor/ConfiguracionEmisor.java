package co.com.agsamericas.digsa.seguridad.emisor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * ANDAMIAJE I1 (S1 lo reemplaza): las dos cadenas del emisor, explícitas para poder dejar pública la salud.
 * 1) Endpoints OIDC/OAuth2. 2) Formulario de ingreso y el resto (en S1 lo sustituye el ingreso de dos pasos con MFA por correo).
 */
@Configuration
class ConfiguracionEmisor {

    @Bean
    @Order(1)
    SecurityFilterChain cadenaEmisor(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer((OAuth2AuthorizationServerConfigurer emisor) -> {
                    http.securityMatcher(emisor.getEndpointsMatcher());
                    emisor.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(r -> r.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cadenaIngreso(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
