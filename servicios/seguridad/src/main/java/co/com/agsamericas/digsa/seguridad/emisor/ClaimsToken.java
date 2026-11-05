package co.com.agsamericas.digsa.seguridad.emisor;

import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * ANDAMIAJE I1 (lo reemplaza la sesión seguridad en S1): pone en el access token la audiencia {@code lume} y los claims de ADR-004 a
 * partir del usuario en memoria. En S1 los claims salen de las tablas de usuarios, perfiles directos y roles adicionales; los perfiles
 * siguen siendo solo los directos.
 */
@Configuration
class ClaimsToken {

    static final String AUDIENCIA = "lume";

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> claimsLume() {
        return ctx -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) {
                return;
            }
            ctx.getClaims().audience(List.of(AUDIENCIA));
            Authentication principal = ctx.getPrincipal();
            if (ctx.getAuthorizationGrantType() != null
                    && "client_credentials".equals(ctx.getAuthorizationGrantType().getValue())) {
                return;
            }
            List<String> perfiles = principal.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring("ROLE_".length()).toUpperCase(Locale.ROOT))
                    .toList();
            ctx.getClaims()
                    .claim("usuario", principal.getName())
                    .claim("nombre", principal.getName())
                    .claim("perfiles", perfiles)
                    .claim("pantallas", List.of("INICIO"));
        };
    }
}
