package co.com.agsamericas.digsa.visitas.usuarios;

import co.com.agsamericas.digsa.comun.eventos.Sobre;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Aplica a {@code usuarios_copia} la foto que publica {@code seguridad} en sus eventos {@code usuario.*} y {@code cuenta.*}
 * (contratos/eventos/seguridad.yaml; ADR-006, decisión D2). Reglas:
 * <ul>
 *   <li><b>Lista blanca de tipos:</b> los que no están aquí se descartan sin error.</li>
 *   <li><b>Regla de versión atómica:</b> un solo UPSERT que solo pisa la fila si la versión que llega es MAYOR que la guardada; un evento viejo
 *       o repetido no cambia nada, aunque llegue tarde o lo apliquen dos hilos a la vez.</li>
 *   <li>Solo los campos que usa visitas. El documento llega solo para los auditores (minimización de seguridad); para los demás es null.</li>
 *   <li>Sin datos personales en logs ni en excepciones.</li>
 * </ul>
 * La idempotencia por id y los reintentos los pone {@code ConsumoEventos} de comun; esto corre dentro de su transacción.
 */
@Component
public class CopiaUsuarios {

    private static final Logger LOG = LoggerFactory.getLogger(CopiaUsuarios.class);

    /** Todos llevan la foto completa del usuario. */
    static final Set<String> TIPOS = Set.of("usuario.creado", "usuario.actualizado", "usuario.estado_cambiado", "usuario.permisos_cambiados",
            "cuenta.bloqueada");

    private final JdbcClient jdbc;

    public CopiaUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true si la copia cambió (la versión era más nueva) */
    public boolean aplicar(Sobre s) {
        if (!TIPOS.contains(s.tipo())) {
            LOG.debug("[copia-usuarios] Tipo {} descartado (no es de la lista blanca)", s.tipo());
            return false;
        }
        JsonNode d = s.datos();
        JsonNode uid = d.get("uid");
        if (uid == null || !uid.canConvertToLong() || uid.asLong() <= 0) {
            throw new IllegalArgumentException("Foto de usuario sin uid válido");
        }
        JsonNode alcance = d.path("alcance");
        int n = jdbc.sql("""
                INSERT INTO usuarios_copia (uid, usuario, nombre, documento, correo, activo, perfiles, alcance_fuerza, alcance_punto,
                                            alcance_regional, version, actualizado)
                VALUES (:uid, :usuario, :nombre, :documento, :correo, :activo, :perfiles, :fuerza, :punto, :regional, :version, now())
                ON CONFLICT (uid) DO UPDATE SET usuario = EXCLUDED.usuario, nombre = EXCLUDED.nombre, documento = EXCLUDED.documento,
                  correo = EXCLUDED.correo, activo = EXCLUDED.activo, perfiles = EXCLUDED.perfiles, alcance_fuerza = EXCLUDED.alcance_fuerza,
                  alcance_punto = EXCLUDED.alcance_punto, alcance_regional = EXCLUDED.alcance_regional, version = EXCLUDED.version,
                  actualizado = now()
                WHERE usuarios_copia.version < EXCLUDED.version""")
                .param("uid", uid.asLong())
                .param("usuario", texto(d, "usuario"))
                .param("nombre", texto(d, "nombre"))
                .param("documento", texto(d, "documento"))
                .param("correo", texto(d, "correo"))
                .param("activo", d.path("activo").asBoolean(false))
                .param("perfiles", perfiles(d.path("perfiles")))
                .param("fuerza", texto(alcance, "fuerza"))
                .param("punto", numero(alcance, "punto"))
                .param("regional", numero(alcance, "regional"))
                .param("version", s.version())
                .update();
        return n == 1;
    }

    /** Nombres de perfil (AUDITOR, COORDINADOR…) → ids de {@link Perfil}. Un nombre desconocido se ignora (no da permisos). */
    static Short[] perfiles(JsonNode lista) {
        List<Short> r = new ArrayList<>();
        if (lista != null && lista.isArray()) {
            for (JsonNode p : lista) {
                for (Perfil perfil : Perfil.values()) {
                    if (perfil.name().equals(p.asString())) {
                        r.add((short) perfil.id());
                    }
                }
            }
        }
        return r.toArray(Short[]::new);
    }

    private static String texto(JsonNode d, String campo) {
        JsonNode v = d.get(campo);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Long numero(JsonNode d, String campo) {
        JsonNode v = d.get(campo);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }
}
