package co.com.agsamericas.digsa.comun.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ConvertidorJwtTest {

    private final ConvertidorJwt convertidor = new ConvertidorJwt();

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("42").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    }

    private static List<String> autoridades(org.springframework.security.authentication.AbstractAuthenticationToken a) {
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void losPerfilesDirectosDanPerfilYLasPantallasDanPantalla() {
        var a = convertidor.convert(token().claim("usuario", "coord.ficticio").claim("perfiles", List.of("coordinador"))
                .claim("pantallas", List.of("AGENDA", "TABLERO")).build());
        assertThat(autoridades(a)).containsExactlyInAnyOrder("PERFIL_COORDINADOR", "PANTALLA_AGENDA", "PANTALLA_TABLERO");
        assertThat(a.getName()).isEqualTo("coord.ficticio");
    }

    /** reglas/Roles: un rol adicional con base Coordinador da pantallas y alcance, nunca PERFIL_COORDINADOR (falla cerrado). */
    @Test
    void unRolAdicionalSinPerfilesDirectosNoDaNingunaAutoridadDePerfil() {
        var a = convertidor.convert(token().claim("perfiles", List.of()).claim("pantallas", List.of("DASHBOARD_RIESGO"))
                .claim("alcance", Map.of("base", "COORDINADOR")).build());
        assertThat(autoridades(a)).containsExactly("PANTALLA_DASHBOARD_RIESGO");
    }

    @Test
    void unaLlamadaEntreServiciosRecibeSusAlcances() {
        var a = convertidor.convert(token().claim("scope", "notificaciones:enviar_secreto lume.humo").build());
        assertThat(autoridades(a)).containsExactlyInAnyOrder("SCOPE_notificaciones:enviar_secreto", "SCOPE_lume.humo");
        assertThat(a.getName()).isEqualTo("42");
    }

    @Test
    void ignoraValoresVacios() {
        var a = convertidor.convert(token().claim("perfiles", List.of(" ", "")).claim("scope", " ").build());
        assertThat(a.getAuthorities()).isEmpty();
    }

    @Test
    void usuarioActualLeeLosClaims() {
        Jwt jwt = token().claim("uid", 7L).claim("usuario", "ana.prueba").claim("nombre", "Ana Prueba")
                .claim("perfiles", List.of("ANALISTA")).claim("pantallas", List.of("INICIO"))
                .claim("alcance", Map.of("nacional", true)).build();
        UsuarioActual u = UsuarioActual.de(jwt);
        assertThat(u.uid()).isEqualTo(7L);
        assertThat(u.usuario()).isEqualTo("ana.prueba");
        assertThat(u.perfiles()).containsExactly("ANALISTA");
        assertThat(u.asignados()).isEmpty();
        assertThat(u.alcance()).containsEntry("nacional", true);
    }
}
