package co.com.agsamericas.digsa.visitas.soporte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import co.com.agsamericas.digsa.comun.auditoria.EventoAuditoria;
import co.com.agsamericas.digsa.reglas.Bitacora;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Ayudas de las pruebas de API de {@code visitas}: peticiones JSON con token, datos con sufijo único (la base se comparte entre las
 * pruebas), usuarios ficticios sembrados en {@code usuarios_copia} y verificación de los eventos de auditoría publicados.
 * Los usuarios sembrados son todos del dominio {@code @visitas-a.prueba.local}.
 */
public abstract class PruebaApi extends PruebaIntegracion {

    private static final AtomicLong SECUENCIA = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final Random AZAR = new Random();

    // ---------------------------------------------------------------- peticiones

    protected ResultActions get(String url, RequestPostProcessor token) throws Exception {
        return mvc.perform(con(get(url), token));
    }

    protected ResultActions post(String url, RequestPostProcessor token, Object cuerpo) throws Exception {
        return mvc.perform(con(post(url), token).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    protected ResultActions put(String url, RequestPostProcessor token, Object cuerpo) throws Exception {
        return mvc.perform(con(put(url), token).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    protected ResultActions delete(String url, RequestPostProcessor token) throws Exception {
        return mvc.perform(con(delete(url), token));
    }

    private static MockHttpServletRequestBuilder con(MockHttpServletRequestBuilder b, RequestPostProcessor token) {
        return token == null ? b : b.with(token);
    }

    // ---------------------------------------------------------------- JSON (sin depender de la versión de Jackson)

    /** Serializa mapas, listas, arreglos, textos, números, booleanos y null a JSON. */
    protected static String json(Object o) {
        StringBuilder s = new StringBuilder();
        escribir(s, o);
        return s.toString();
    }

    private static void escribir(StringBuilder s, Object o) {
        if (o == null) {
            s.append("null");
        } else if (o instanceof Map<?, ?> m) {
            s.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) {
                    s.append(',');
                }
                primero = false;
                texto(s, String.valueOf(e.getKey()));
                s.append(':');
                escribir(s, e.getValue());
            }
            s.append('}');
        } else if (o instanceof Collection<?> c) {
            s.append('[');
            boolean primero = true;
            for (Object x : c) {
                if (!primero) {
                    s.append(',');
                }
                primero = false;
                escribir(s, x);
            }
            s.append(']');
        } else if (o instanceof Object[] a) {
            escribir(s, List.of(a));
        } else if (o instanceof Number || o instanceof Boolean) {
            s.append(o);
        } else {
            texto(s, o.toString());
        }
    }

    private static void texto(StringBuilder s, String t) {
        s.append('"');
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) {
                        s.append(String.format("\\u%04x", (int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        s.append('"');
    }

    // ---------------------------------------------------------------- datos únicos

    protected static long secuencia() {
        return SECUENCIA.incrementAndGet();
    }

    protected static String unico(String prefijo) {
        return prefijo + secuencia();
    }

    /** Código de letras mayúsculas que no está en uso como fuerza ni como valor de catálogo. */
    protected String letras(int n) {
        while (true) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                b.append((char) ('A' + AZAR.nextInt(26)));
            }
            String c = b.toString();
            long uso = jdbc.sql("SELECT (SELECT count(*) FROM par_fuerzas WHERE codigo = :c) "
                    + "+ (SELECT count(*) FROM catalogo_valores WHERE codigo = :c)").param("c", c).query(Long.class).single();
            if (uso == 0) {
                return c;
            }
        }
    }

    // ---------------------------------------------------------------- usuarios ficticios (usuarios_copia)

    /** Uid libre, fuera del rango de los otros datos de prueba. */
    protected static long nuevoUid() {
        return 8_000_000_000L + secuencia();
    }

    /** Siembra un usuario ficticio; devuelve su uid. {@code perfiles}: ids de {@code reglas/Perfil}. */
    protected long sembrarUsuario(String nombre, boolean activo, int[] perfiles, String alcanceFuerza, Long alcancePunto, Long alcanceRegional) {
        long uid = nuevoUid();
        String arreglo = java.util.Arrays.stream(perfiles).mapToObj(Integer::toString).collect(Collectors.joining(",", "{", "}"));
        jdbc.sql("INSERT INTO usuarios_copia (uid, usuario, nombre, documento, correo, activo, perfiles, alcance_fuerza, alcance_punto, "
                + "alcance_regional, version) VALUES (:uid, :us, :n, :d, :c, :a, CAST(:p AS smallint[]), :af, :ap, :ar, 1)")
                .param("uid", uid).param("us", "u" + uid).param("n", nombre).param("d", "D" + uid)
                .param("c", "u" + uid + "@visitas-a.prueba.local").param("a", activo).param("p", arreglo)
                .param("af", alcanceFuerza).param("ap", alcancePunto, java.sql.Types.BIGINT).param("ar", alcanceRegional, java.sql.Types.BIGINT)
                .update();
        return uid;
    }

    protected long sembrarAuditor(String nombre, boolean activo) {
        return sembrarUsuario(nombre, activo, new int[]{3}, null, null, null);
    }

    protected long sembrarUsuario(String nombre, boolean activo, int... perfiles) {
        return sembrarUsuario(nombre, activo, perfiles, null, null, null);
    }

    protected String documentoDe(long uid) {
        return jdbc.sql("SELECT documento FROM usuarios_copia WHERE uid = :u").param("u", uid).query(String.class).single();
    }

    // ---------------------------------------------------------------- auditoría

    protected List<EventoAuditoria> eventos(String tipo) {
        return auditoria.eventos().stream().filter(e -> e.tipo().equals(tipo)).toList();
    }

    /**
     * El evento de ese tipo cuyo detalle contiene el fragmento: verifica servicio {@code visitas}, operación, y uid y usuario del
     * token (el usuario del helper {@code token(uid)} es {@code "u" + uid}). Falla si no existe.
     */
    protected EventoAuditoria evento(String tipo, Bitacora.Operacion operacion, long uid, String fragmento) {
        EventoAuditoria e = eventos(tipo).stream().filter(x -> x.detalle() != null && x.detalle().contains(fragmento)).findFirst()
                .orElseThrow(() -> new AssertionError("Sin evento «" + tipo + "» con «" + fragmento + "»; hay: " + auditoria.eventos()));
        assertThat(e.servicio()).isEqualTo("visitas");
        assertThat(e.operacion()).isEqualTo(operacion);
        assertThat(e.uid()).isEqualTo(uid);
        assertThat(e.usuario()).isEqualTo("u" + uid);
        return e;
    }
}
