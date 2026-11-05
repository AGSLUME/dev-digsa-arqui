package co.com.agsamericas.digsa.seguridad.emisor;

import co.com.agsamericas.digsa.comun.seguridad.ClaimsLume;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioUsuarios;
import co.com.agsamericas.digsa.seguridad.identidad.Usuario;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Claims de los tokens de LUME (ADR-004), desde las mismas tablas que administra LUME. El principal de la autorización es el {@code sid}
 * (ver {@code identidad.ServicioIngreso}); de ahí salen la sesión y el usuario.
 * <ul>
 *   <li>{@code sub} y {@code uid}: el id numérico del usuario (el mismo del monolito).</li>
 *   <li>{@code perfiles}: solo los directos (nombres del enum {@link Perfil}); la base de un rol adicional nunca da una autoridad.</li>
 *   <li>{@code pantallas}: nombres del enum {@link Pantallas} (perfiles directos y roles adicionales); {@code asignados}: ids asignados en
 *       {@code usuarios_perfiles}; {@code alcance}: estructurado (Analista = consulta nacional, {@code tipo: todo}).</li>
 *   <li>{@code aud}: los destinatarios del token de usuario (gateway, servicios y monolito, {@code lume.emisor.audiencias}); en un token de
 *       máquina, el servicio de cada alcance ({@code notificaciones:enviar_secreto} → {@code notificaciones}).</li>
 * </ul>
 * Los valores van en colecciones mutables (ArrayList, LinkedHashMap): los claims quedan en los metadatos de la autorización guardada,
 * que el emisor relee con su lista de tipos permitidos de Jackson (List.of no está en ella).
 * Si la sesión ya no está abierta o el usuario dejó de estar activo, el emisor no emite (ni refresca) el token: {@code invalid_grant}. Así
 * la revocación es inmediata también para el refresco, sin esperar a que venza el refresh token (bloqueante 2).
 */
@Component
class ClaimsToken implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final RepositorioSesiones sesiones;
    private final RepositorioUsuarios usuarios;
    private final Clock reloj;
    private final List<String> audiencias;

    ClaimsToken(RepositorioSesiones sesiones, RepositorioUsuarios usuarios, Clock reloj,
                @Value("${lume.emisor.audiencias:gateway,seguridad,visitas,reportes,notificaciones,monolito,hola}") List<String> audiencias) {
        this.sesiones = sesiones;
        this.usuarios = usuarios;
        this.reloj = reloj;
        this.audiencias = audiencias.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList();
    }

    @Override
    public void customize(JwtEncodingContext ctx) {
        boolean acceso = OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(ctx.getTokenType().getValue());
        if (!acceso && !idToken) {
            return;
        }
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(ctx.getAuthorizationGrantType())) {
            if (acceso) {
                ctx.getClaims().audience(new ArrayList<>(audienciasDeAlcances(ctx.getAuthorizedScopes())));
            }
            return;
        }
        UUID sid = sid(ctx.getPrincipal().getName()).orElseThrow(ClaimsToken::sinSesion);
        RepositorioSesiones.Fila sesion = sesiones.porId(sid).filter(RepositorioSesiones.Fila::abierta).orElseThrow(ClaimsToken::sinSesion);
        Usuario u = usuarios.porId(sesion.usuarioId()).filter(Usuario::activo).filter(x -> !x.bloqueado(reloj.instant()))
                .orElseThrow(ClaimsToken::sinSesion);
        ctx.getClaims()
                .subject(String.valueOf(u.id()))
                .claim(ClaimsLume.UID, u.id())
                .claim(ClaimsLume.USUARIO, u.usuario())
                .claim(ClaimsLume.NOMBRE, u.nombre())
                .claim(ClaimsLume.SID, sid.toString());
        if (acceso) {
            ctx.getClaims()
                    .audience(new ArrayList<>(audiencias))
                    .claim(ClaimsLume.PERFILES, u.perfiles().stream().map(Perfil::name).sorted().collect(Collectors.toCollection(ArrayList::new)))
                    .claim(ClaimsLume.PANTALLAS, Arrays.stream(Pantallas.values()).filter(u.pantallas()::contains).map(Pantallas::name)
                            .collect(Collectors.toCollection(ArrayList::new)))
                    .claim(ClaimsLume.ASIGNADOS, new ArrayList<>(u.asignados()))
                    .claim(ClaimsLume.ALCANCE, alcance(u.alcance()));
        }
    }

    /** El alcance de datos como mapa (reglas/Alcance): {@code tipo} y, según el tipo, su dato. Desconocido = {@code ninguno} (falla cerrado). */
    static Map<String, Object> alcance(Alcance a) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (a) {
            case Alcance.Todo t -> m.put("tipo", "todo");
            case Alcance.Opl o -> m.put("tipo", "opl");
            case Alcance.Fuerza f -> {
                m.put("tipo", "fuerza");
                m.put("fuerza", f.codigo());
            }
            case Alcance.Regional r -> {
                m.put("tipo", "regional");
                m.put("regional", r.id());
            }
            case Alcance.Esm e -> {
                m.put("tipo", "esm");
                m.put("punto", e.puntoId());
            }
            case Alcance.Equipo e -> {
                m.put("tipo", "equipo");
                m.put("auditor", e.usuarioId());
            }
            default -> m.put("tipo", "ninguno");
        }
        return m;
    }

    /** {@code servicio:operacion} → {@code servicio}. Un alcance sin servicio no da audiencia (el token no sirve en ningún servicio). */
    static List<String> audienciasDeAlcances(Collection<String> alcances) {
        Set<String> r = new LinkedHashSet<>();
        for (String s : alcances) {
            int i = s.indexOf(':');
            if (i > 0) {
                r.add(s.substring(0, i));
            }
        }
        return List.copyOf(r);
    }

    private static Optional<UUID> sid(String principal) {
        try {
            return Optional.of(UUID.fromString(principal));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static OAuth2AuthenticationException sinSesion() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "La sesión ya no está abierta.", null));
    }
}
