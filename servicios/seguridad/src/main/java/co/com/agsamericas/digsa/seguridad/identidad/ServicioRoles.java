package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Roles adicionales parametrizables (Anexo 5, 1.12), portado de {@code identidad.ServicioRoles} del monolito: un rol = un perfil base (que
 * fija el alcance de datos) + un subconjunto de las pantallas visibles de esa base. No da la identidad de su base (reglas/Roles). Como en
 * {@link ServicioUsuarios}, la bitácora se escribe después del commit.
 */
@Service
public class ServicioRoles {

    public record Datos(String nombre, String descripcion, Integer baseId, List<String> pantallas) {
    }

    /** Un perfil base que puede ser base de roles, con sus pantallas elegibles. */
    public record Base(int id, String nombre, List<Pantallas> pantallas) {
    }

    /** Un rol adicional con cuántos usuarios activos lo tienen. */
    public record Rol(int id, String nombre, String descripcion, int baseId, boolean activo, List<String> pantallas, long usuarios) {
    }

    private record Previo(String nombre, String descripcion, Set<String> pantallas) {
    }

    private final JdbcClient jdbc;
    private final RepositorioUsuarios usuarios;
    private final BitacoraSeguridad bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioRoles(JdbcClient jdbc, RepositorioUsuarios usuarios, BitacoraSeguridad bitacora, TransactionTemplate tx, Clock reloj) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Perfiles que pueden ser base, con sus pantallas elegibles. */
    public List<Base> bases() {
        Map<Integer, String> nombres = usuarios.nombresPerfiles();
        List<Base> bases = new ArrayList<>();
        for (Perfil p : Perfil.values()) {
            if (Roles.BASES.contains(p)) {
                bases.add(new Base(p.id(), nombres.getOrDefault(p.id(), p.nombre()), List.copyOf(Roles.elegibles(p))));
            }
        }
        return bases;
    }

    /** Los roles creados (activos primero), con cuántos usuarios activos los tienen. */
    public List<Rol> roles() {
        return jdbc.sql("""
                SELECT p.id, p.nombre, p.descripcion, p.base_id, p.activo,
                       COALESCE((SELECT string_agg(pp.pantalla, ',' ORDER BY pp.pantalla) FROM perfiles_pantallas pp WHERE pp.perfil_id = p.id), '') AS pantallas,
                       (SELECT count(*) FROM usuarios_perfiles up JOIN usuarios u ON u.id = up.usuario_id WHERE up.perfil_id = p.id AND u.activo) AS usuarios
                FROM perfiles p WHERE p.base_id IS NOT NULL ORDER BY p.activo DESC, p.nombre""")
                .query((rs, n) -> {
                    String ps = rs.getString("pantallas");
                    return new Rol(rs.getInt("id"), rs.getString("nombre"), rs.getString("descripcion"), rs.getInt("base_id"), rs.getBoolean("activo"),
                            ps.isEmpty() ? List.of() : List.of(ps.split(",")), rs.getLong("usuarios"));
                }).list();
    }

    public int crear(Datos d, Permisos.Quien quien) {
        String nombre = nombre(d.nombre(), 0);
        String descripcion = descripcion(d.descripcion());
        Perfil base = d.baseId() == null ? null : Perfil.deId(d.baseId()).orElse(null);
        Set<Pantallas> pantallas = pantallas(base, d.pantallas()); // falla si la base no es válida: base no es nula desde aquí
        int id;
        try {
            id = tx.execute(t -> {
                int nuevo = jdbc.sql("""
                        INSERT INTO perfiles (id, nombre, descripcion, activo, base_id, creado_por, creado)
                        VALUES (nextval('perfiles_adicionales_id_seq'), :n, :d, true, :b, :u, :ahora) RETURNING id""")
                        .param("n", nombre).param("d", descripcion).param("b", base.id()).param("u", quien.usuario())
                        .param("ahora", OffsetDateTime.now(reloj)).query(Integer.class).single();
                guardarPantallas(nuevo, pantallas);
                return nuevo;
            });
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        bitacora.escribir(Bitacora.Operacion.INSERCION, "rol_creado", "Rol " + id + " «" + nombre + "» con base " + base.nombre() + "; pantallas: "
                + codigos(pantallas), quien.id(), quien.usuario(), null);
        return id;
    }

    public void editar(int id, Datos d, Permisos.Quien quien) {
        String nombre = nombre(d.nombre(), id);
        String descripcion = descripcion(d.descripcion());
        Previo antes;
        Set<Pantallas> pantallas;
        try {
            record Hecho(Previo antes, Set<Pantallas> pantallas) {
            }
            Hecho h = tx.execute(t -> {
                // FOR NO KEY UPDATE: serializa las ediciones del rol sin frenar las asignaciones de usuarios (KEY SHARE de la FK)
                Map<String, Object> fila = jdbc.sql("SELECT base_id, nombre, descripcion FROM perfiles WHERE id = :id AND base_id IS NOT NULL FOR NO KEY UPDATE")
                        .param("id", id).query().listOfRows().stream().findFirst()
                        .orElseThrow(() -> ErrorNegocio.noEncontrado("El rol no existe."));
                Perfil base = Perfil.deId(((Number) fila.get("base_id")).intValue()).orElseThrow();
                if (d.baseId() != null && d.baseId() != base.id()) {
                    throw ErrorNegocio.validacion("El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.");
                }
                Set<Pantallas> nuevas = pantallas(base, d.pantallas());
                Set<String> previas = new TreeSet<>(jdbc.sql("SELECT pantalla FROM perfiles_pantallas WHERE perfil_id = :id").param("id", id)
                        .query(String.class).list());
                jdbc.sql("UPDATE perfiles SET nombre = :n, descripcion = :d WHERE id = :id").param("n", nombre).param("d", descripcion)
                        .param("id", id).update();
                jdbc.sql("DELETE FROM perfiles_pantallas WHERE perfil_id = :id").param("id", id).update();
                guardarPantallas(id, nuevas);
                return new Hecho(new Previo((String) fila.get("nombre"), (String) fila.get("descripcion"), previas), nuevas);
            });
            antes = h.antes();
            pantallas = h.pantallas();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("nombre", antes.nombre());
        a.put("descripcion", antes.descripcion());
        a.put("pantallas", antes.pantallas());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nombre", nombre);
        b.put("descripcion", descripcion);
        b.put("pantallas", new TreeSet<>(codigos(pantallas)));
        bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "rol_editado", "Rol " + id, a, b, Set.of(), quien.id(), quien.usuario());
    }

    /** Activa o inactiva el rol: inactivo, deja de dar acceso desde la siguiente petición de sus usuarios. @return usuarios activos con él */
    public long cambiarEstado(int id, boolean activo, Permisos.Quien quien) {
        record Hecho(boolean antes, long conRol) {
        }
        Hecho h = tx.execute(t -> {
            Boolean previo = jdbc.sql("SELECT activo FROM perfiles WHERE id = :id AND base_id IS NOT NULL FOR NO KEY UPDATE").param("id", id)
                    .query(Boolean.class).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("El rol no existe."));
            jdbc.sql("UPDATE perfiles SET activo = :a WHERE id = :id").param("a", activo).param("id", id).update();
            long n = jdbc.sql("SELECT count(*) FROM usuarios_perfiles up JOIN usuarios u ON u.id = up.usuario_id WHERE up.perfil_id = :id AND u.activo")
                    .param("id", id).query(Long.class).single();
            return new Hecho(previo, n);
        });
        bitacora.escribirCambios(Bitacora.Operacion.ACTUALIZACION, "rol_estado", "Rol " + id + (activo ? " activado" : " inactivado") + " ("
                + h.conRol() + " usuarios activos lo tienen)", Map.of("activo", h.antes()), Map.of("activo", activo), Set.of(), quien.id(),
                quien.usuario());
        return h.conRol();
    }

    private void guardarPantallas(int id, Set<Pantallas> pantallas) {
        jdbc.sql("INSERT INTO perfiles_pantallas (perfil_id, pantalla) SELECT :id, unnest(CAST(:p AS varchar[]))").param("id", id)
                .param("p", codigos(pantallas).toArray(String[]::new)).update();
    }

    private String nombre(String escrito, int excepto) {
        String n = escrito == null ? "" : escrito.strip();
        if (n.length() < 3 || n.length() > 60) {
            throw ErrorNegocio.validacion("El nombre del rol debe tener de 3 a 60 caracteres.");
        }
        if (usuarios.nombresPerfiles().entrySet().stream().anyMatch(e -> e.getKey() != excepto && e.getValue().equalsIgnoreCase(n))) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        return n;
    }

    private static String descripcion(String escrita) {
        String d = escrita == null ? "" : escrita.strip();
        if (d.length() > 200) {
            throw ErrorNegocio.validacion("La descripción tiene máximo 200 caracteres.");
        }
        return d.isEmpty() ? null : d;
    }

    private static Set<Pantallas> pantallas(Perfil base, List<String> codigos) {
        EnumSet<Pantallas> r = EnumSet.noneOf(Pantallas.class);
        for (String c : codigos == null ? List.<String>of() : codigos) {
            try {
                r.add(Pantallas.valueOf(c));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ErrorNegocio.validacion("Hay una pantalla no válida.");
            }
        }
        Roles.problema(base, r).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        return r;
    }

    private static List<String> codigos(Set<Pantallas> pantallas) {
        return pantallas.stream().map(Pantallas::name).toList();
    }
}
