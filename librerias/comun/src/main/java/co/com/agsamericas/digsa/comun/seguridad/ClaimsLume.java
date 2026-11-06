package co.com.agsamericas.digsa.comun.seguridad;

/** Nombres de los claims del access token de LUME (ADR-004). Los emite {@code seguridad}; los leen todos los servicios y el monolito. */
public final class ClaimsLume {

    public static final String UID = "uid";
    public static final String USUARIO = "usuario";
    public static final String NOMBRE = "nombre";
    /** Solo perfiles directos. La base de un rol adicional nunca va aquí (reglas/Roles: falla cerrado). */
    public static final String PERFILES = "perfiles";
    public static final String PANTALLAS = "pantallas";
    public static final String ASIGNADOS = "asignados";
    public static final String ALCANCE = "alcance";
    public static final String SID = "sid";

    private ClaimsLume() {
    }
}
