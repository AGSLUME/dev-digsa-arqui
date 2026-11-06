package co.com.agsamericas.digsa.seguridad.emisor;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import com.nimbusds.jose.jwk.JWK;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Tokens del emisor en base (bloqueante 2): las autorizaciones (códigos, access y refresh tokens) van en {@code oauth2_authorization} y
 * las claves de firma en {@code jwk} ({@link AlmacenJwk}); un reinicio o una segunda réplica no invalidan las sesiones. Y la validación de
 * los JWT que recibe el propio servicio ({@code /api/sesion}, {@code /api/cuenta}): firma con las mismas claves, emisor y audiencia
 * {@code seguridad} (bloqueante 3).
 */
@Configuration
class ConfiguracionTokens {

    @Bean
    OAuth2AuthorizationService autorizaciones(JdbcOperations jdbc, RegisteredClientRepository clientes) {
        // El mismo mapeador JSON que arma el emisor, con dos tipos más en la lista de permitidos: los números de los claims de LUME
        // (uid, ids de alcance y asignados), que quedan en los metadatos de la autorización guardada. Nada más se abre.
        BasicPolymorphicTypeValidator.Builder tipos = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Long.class)
                .allowIfSubType(Integer.class);
        JsonMapper json = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(ConfiguracionTokens.class.getClassLoader(), tipos))
                .build();
        JdbcOAuth2AuthorizationService s = new JdbcOAuth2AuthorizationService(jdbc, clientes);
        s.setAuthorizationRowMapper(new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clientes, json));
        s.setAuthorizationParametersMapper(new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(json));
        return s;
    }

    /**
     * Firma siempre con la clave vigente: es la única con parte privada (las retiradas se publican solo para verificar). Sin el selector,
     * tras una rotación el codificador encontraría dos claves RS256.
     */
    @Bean
    JwtEncoder codificadorJwt(AlmacenJwk claves) {
        NimbusJwtEncoder e = new NimbusJwtEncoder(claves);
        e.setJwkSelector(lista -> lista.stream().filter(JWK::isPrivate).findFirst()
                .orElseThrow(() -> new IllegalStateException("[jwk] No hay una clave de firma vigente")));
        return e;
    }

    @Bean
    JwtDecoder decodificadorJwt(AlmacenJwk claves, @Value("${spring.security.oauth2.authorizationserver.issuer}") String emisor) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSource(claves).build();
        OAuth2TokenValidator<Jwt> audiencia = new JwtClaimValidator<java.util.List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains("seguridad"));
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(emisor), audiencia));
        return d;
    }

    /** Purga de autorizaciones vencidas (por su expiración efectiva) y de códigos MFA vencidos hace más de un día. */
    @Component
    static class Purga {

        private final JdbcClient jdbc;
        private final Clock reloj;

        Purga(JdbcClient jdbc, Clock reloj) {
            this.jdbc = jdbc;
            this.reloj = reloj;
        }

        @Scheduled(cron = "${lume.emisor.purga-cron:0 30 * * * *}", zone = "America/Bogota")
        void purgar() {
            var ahora = reloj.instant().atOffset(ZoneOffset.UTC);
            jdbc.sql("""
                    DELETE FROM oauth2_authorization
                    WHERE COALESCE(refresh_token_expires_at, access_token_expires_at, authorization_code_expires_at) < :a""")
                    .param("a", ahora).update();
            jdbc.sql("DELETE FROM mfa_codigos WHERE vence < :a").param("a", ahora.minusDays(1)).update();
        }
    }
}
