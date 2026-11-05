package co.com.agsamericas.digsa.comun.seguridad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Autoridades a partir del token, igual que {@code FiltroSesion} del monolito: {@code PERFIL_<X>} solo de los perfiles directos
 * ({@link ClaimsLume#PERFILES}) y {@code PANTALLA_<X>} de las pantallas. Un rol adicional da pantallas, nunca la identidad de su
 * perfil base. Las llamadas entre servicios ({@code client_credentials}) traen {@code scope} y reciben {@code SCOPE_<x>}.
 */
public class ConvertidorJwt implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> autoridades = new ArrayList<>();
        agregar(autoridades, "PERFIL_", jwt.getClaimAsStringList(ClaimsLume.PERFILES));
        agregar(autoridades, "PANTALLA_", jwt.getClaimAsStringList(ClaimsLume.PANTALLAS));
        for (String s : alcances(jwt)) {
            autoridades.add(new SimpleGrantedAuthority("SCOPE_" + s));
        }
        String nombre = jwt.getClaimAsString(ClaimsLume.USUARIO);
        return new JwtAuthenticationToken(jwt, autoridades, nombre == null ? jwt.getSubject() : nombre);
    }

    /**
     * Los alcances del token: {@code scope} como texto separado por espacios (RFC 6749) o como lista JSON, que es como lo emite Spring
     * Authorization Server en los tokens de máquina.
     */
    static List<String> alcances(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        List<String> r = new ArrayList<>();
        if (scope instanceof Collection<?> lista) {
            for (Object s : lista) {
                if (s != null && !s.toString().isBlank()) {
                    r.add(s.toString().trim());
                }
            }
        } else if (scope instanceof String texto && !texto.isBlank()) {
            r.addAll(List.of(texto.trim().split("\\s+")));
        }
        return r;
    }

    private static void agregar(List<GrantedAuthority> destino, String prefijo, List<String> valores) {
        if (valores == null) {
            return;
        }
        for (String v : valores) {
            if (v != null && !v.isBlank()) {
                destino.add(new SimpleGrantedAuthority(prefijo + v.trim().toUpperCase(Locale.ROOT)));
            }
        }
    }
}
