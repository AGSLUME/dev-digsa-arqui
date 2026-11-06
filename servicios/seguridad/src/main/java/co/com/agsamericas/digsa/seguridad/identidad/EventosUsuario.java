package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.eventos.PublicadorEventos;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import co.com.agsamericas.digsa.seguridad.identidad.RepositorioSesiones.Motivo;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Eventos de seguridad hacia {@code seguridad.eventos} (ADR-006, {@code contratos/eventos/seguridad.yaml}), por el outbox y siempre
 * dentro de la transacción del cambio.
 * <ul>
 *   <li>{@code usuario.*} y {@code cuenta.bloqueada} llevan la {@link Foto} completa del usuario (no un diff), con la {@code version} del
 *       agregado: las copias de visitas, reportes y el monolito son idempotentes y se pueden reconstruir.</li>
 *   <li>{@code sesion.cerrada} lleva {@code {sid, uid, motivo}}: el gateway cierra la sesión sin esperar su caché.</li>
 * </ul>
 * La versión se sube con {@code UPDATE ... RETURNING}, que bloquea la fila del usuario: dos cambios del mismo usuario se serializan y la
 * foto leída después del bloqueo es la del orden de commit. Quien cambia varios usuarios a la vez los bloquea antes con
 * {@link #bloquearEnOrden(Collection)}, en el orden fijo del servicio (administradores activos → resto por id).
 */
@Component
public class EventosUsuario {

    /**
     * Lo que ven los consumidores de un usuario. Sin teléfono, hash de clave, tokens, intentos ni datos de sesión. {@code documento} es
     * un dato personal (Ley 1581): cada consumidor guarda solo lo que usa. {@code bloqueado} es informativo (al momento del evento); el
     * bloqueo vence solo, así que cuenta {@code bloqueadoHasta}.
     */
    public record Foto(long uid, String usuario, String nombre, String documento, String correo, boolean activo, boolean bloqueado,
                       Instant bloqueadoHasta, List<String> perfiles, List<RolFoto> roles, List<String> pantallas,
                       Map<String, Object> alcance, List<Integer> asignados) {

        /** Sin datos personales: lo único que se puede imprimir. */
        @Override
        public String toString() {
            return "Foto[uid=" + uid + "]";
        }
    }

    /** Un rol adicional del usuario: su id, su perfil base y las pantallas que da. */
    public record RolFoto(int id, String base, List<String> pantallas) {
    }

    private final JdbcClient jdbc;
    private final RepositorioUsuarios usuarios;
    private final PublicadorEventos publicador;
    private final Clock reloj;

    public EventosUsuario(JdbcClient jdbc, RepositorioUsuarios usuarios, PublicadorEventos publicador, Clock reloj) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.publicador = publicador;
        this.reloj = reloj;
    }

    /** Sube la versión del usuario y publica su foto con ese tipo ({@code usuario.creado}, {@code cuenta.bloqueada}, ...). */
    public void publicar(String tipo, long uid) {
        long version = jdbc.sql("UPDATE usuarios SET version = version + 1 WHERE id = :id RETURNING version").param("id", uid)
                .query(Long.class).single();
        Usuario u = usuarios.porId(uid).orElseThrow();
        publicador.publicar(tipo, "usuario:" + uid, version, foto(u, reloj.instant()));
    }

    /**
     * Bloquea a varios usuarios en el orden fijo (administradores activos → los demás por id) antes de publicar sus eventos. Evita el
     * interbloqueo con la edición de un usuario, que bloquea en ese mismo orden.
     */
    public void bloquearEnOrden(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        usuarios.bloquearAdministradoresActivos();
        jdbc.sql("SELECT id FROM usuarios WHERE id = ANY(:ids) ORDER BY id FOR UPDATE").param("ids", ids.toArray(Long[]::new))
                .query(Long.class).list();
    }

    /**
     * Primer paso de quien asigna roles a un usuario: bloquea (FOR SHARE) los roles adicionales que asigna. Así una edición del mismo rol
     * (FOR NO KEY UPDATE) espera, o se espera: si no, el rol editado no vería todavía al usuario nuevo y su foto quedaría con las pantallas
     * viejas. Orden global: roles → administradores activos → usuarios por id.
     */
    public void bloquearRoles(Collection<Integer> asignados) {
        Integer[] roles = asignados.stream().filter(id -> id >= 100).sorted().toArray(Integer[]::new);
        if (roles.length > 0) {
            jdbc.sql("SELECT id FROM perfiles WHERE id = ANY(:ids) AND base_id IS NOT NULL ORDER BY id FOR SHARE").param("ids", roles)
                    .query(Integer.class).list();
        }
    }

    /** Usuarios con un perfil o rol asignado, por id. */
    public List<Long> conAsignado(int perfilId) {
        return jdbc.sql("SELECT usuario_id FROM usuarios_perfiles WHERE perfil_id = :p ORDER BY usuario_id").param("p", perfilId)
                .query(Long.class).list();
    }

    /** Una sesión cerrada (cada sesión se cierra una sola vez: versión 1). */
    public void sesionCerrada(UUID sid, long uid, Motivo motivo) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("sid", sid.toString());
        datos.put("uid", uid);
        datos.put("motivo", motivo.valor());
        publicador.publicar("sesion.cerrada", "sesion:" + sid, 1, datos);
    }

    /** La foto de un usuario, la misma de los eventos y de la exportación interna. */
    public static Foto foto(Usuario u, Instant ahora) {
        List<String> perfiles = u.perfiles().stream().map(Perfil::name).sorted().toList();
        List<RolFoto> roles = u.rolesAdicionales().stream().sorted(Comparator.comparingInt(Roles.Adicional::id))
                .map(r -> new RolFoto(r.id(), r.base().name(), ordenadas(r.pantallas()))).toList();
        Map<String, Object> alcance = new LinkedHashMap<>();
        alcance.put("tipo", tipo(u.alcance()));
        alcance.put("fuerza", u.alcanceFuerza());
        alcance.put("punto", u.alcancePunto());
        alcance.put("regional", u.alcanceRegional());
        return new Foto(u.id(), u.usuario(), u.nombre(), u.documento(), u.correo(), u.activo(), u.bloqueado(ahora), u.bloqueadoHasta(),
                perfiles, roles, ordenadas(u.pantallas()), alcance, new ArrayList<>(u.asignados()));
    }

    private static List<String> ordenadas(Collection<Pantallas> pantallas) {
        return Arrays.stream(Pantallas.values()).filter(pantallas::contains).map(Pantallas::name).toList();
    }

    /** El tipo de alcance, con los mismos nombres del claim {@code alcance} del token. */
    private static String tipo(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> "todo";
            case Alcance.Opl o -> "opl";
            case Alcance.Fuerza f -> "fuerza";
            case Alcance.Regional r -> "regional";
            case Alcance.Esm e -> "esm";
            case Alcance.Equipo e -> "equipo";
            default -> "ninguno";
        };
    }
}
