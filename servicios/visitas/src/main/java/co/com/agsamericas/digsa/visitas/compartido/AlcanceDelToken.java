package co.com.agsamericas.digsa.visitas.compartido;

import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.reglas.Alcance;
import java.util.Map;

/**
 * El alcance de datos del usuario a partir del claim {@code alcance} del token, inverso de {@code ClaimsToken.alcance} de seguridad:
 * {@code {tipo: todo|opl|fuerza|regional|esm|equipo|ninguno}} y su dato ({@code fuerza}, {@code regional}, {@code punto}, {@code auditor}).
 * Reemplaza a {@code UsuarioSesion.alcanceActual()} del monolito. Falla cerrado: lo desconocido o mal formado es {@link Alcance.Ninguno}.
 */
public final class AlcanceDelToken {

    private AlcanceDelToken() {
    }

    public static Alcance de(UsuarioActual u) {
        return u == null ? new Alcance.Ninguno() : de(u.alcance());
    }

    public static Alcance de(Map<String, Object> claim) {
        if (claim == null || !(claim.get("tipo") instanceof String tipo)) {
            return new Alcance.Ninguno();
        }
        try {
            return switch (tipo) {
                case "todo" -> new Alcance.Todo();
                case "opl" -> new Alcance.Opl();
                case "fuerza" -> claim.get("fuerza") instanceof String f && !f.isBlank() ? new Alcance.Fuerza(f) : new Alcance.Ninguno();
                case "regional" -> numero(claim.get("regional")) instanceof Long r ? new Alcance.Regional(r) : new Alcance.Ninguno();
                case "esm" -> numero(claim.get("punto")) instanceof Long p ? new Alcance.Esm(p) : new Alcance.Ninguno();
                case "equipo" -> numero(claim.get("auditor")) instanceof Long a ? new Alcance.Equipo(a) : new Alcance.Ninguno();
                default -> new Alcance.Ninguno();
            };
        } catch (RuntimeException e) {
            return new Alcance.Ninguno();
        }
    }

    /** Id positivo y numérico (como lo emite seguridad); un texto, cero o negativo no sirve (falla cerrado, como Alcance.de). */
    private static Long numero(Object o) {
        return o instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
    }
}
