package co.com.agsamericas.digsa.comun.seguridad;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * El usuario de la petición, leído de los claims del token (ADR-004). Equivale a {@code UsuarioSesion} + {@code UsuarioSesion.Acceso}
 * del monolito.
 *
 * @param uid       id numérico (el mismo del monolito)
 * @param usuario   nombre de usuario
 * @param nombre    nombre para mostrar
 * @param perfiles  perfiles directos (los únicos que dan autoridades {@code PERFIL_*})
 * @param pantallas pantallas habilitadas (de los perfiles directos o de los roles adicionales)
 * @param asignados ids asignados (puntos, regionales) según el perfil
 * @param alcance   alcance estructurado de datos (fuerza, punto, regional)
 */
public record UsuarioActual(Long uid, String usuario, String nombre, List<String> perfiles, List<String> pantallas, List<String> asignados,
                            Map<String, Object> alcance) {

    public static UsuarioActual de(Jwt jwt) {
        Number uid = jwt.getClaim(ClaimsLume.UID);
        Map<String, Object> alcance = jwt.getClaimAsMap(ClaimsLume.ALCANCE);
        return new UsuarioActual(
                uid == null ? null : uid.longValue(),
                jwt.getClaimAsString(ClaimsLume.USUARIO),
                jwt.getClaimAsString(ClaimsLume.NOMBRE),
                lista(jwt, ClaimsLume.PERFILES),
                lista(jwt, ClaimsLume.PANTALLAS),
                lista(jwt, ClaimsLume.ASIGNADOS),
                alcance == null ? Map.of() : Map.copyOf(alcance));
    }

    /** El usuario de la petición en curso, si viene autenticado con un JWT. */
    public static Optional<UsuarioActual> actual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken t ? Optional.of(de(t.getToken())) : Optional.empty();
    }

    private static List<String> lista(Jwt jwt, String claim) {
        List<String> l = jwt.getClaimAsStringList(claim);
        return l == null ? List.of() : List.copyOf(l);
    }
}
