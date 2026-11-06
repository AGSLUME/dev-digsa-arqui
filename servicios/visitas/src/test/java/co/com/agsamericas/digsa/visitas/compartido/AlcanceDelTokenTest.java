package co.com.agsamericas.digsa.visitas.compartido;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.comun.seguridad.UsuarioActual;
import co.com.agsamericas.digsa.reglas.Alcance;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VA1.6: {@link AlcanceDelToken}, inverso de {@code ClaimsToken.alcance(...)} de seguridad. Sin Spring. Los mapas replican lo que emite
 * seguridad: {tipo: todo|opl|fuerza(fuerza)|regional(regional)|esm(punto)|equipo(auditor)|ninguno}. Lo desconocido falla cerrado (Ninguno).
 */
class AlcanceDelTokenTest {

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static UsuarioActual usuario(Map<String, Object> alcance) {
        return new UsuarioActual(7L, "u7", "Persona", List.of(), List.of(), List.of(), alcance);
    }

    @Test
    @DisplayName("todo y opl no necesitan dato")
    void sinDato() {
        assertThat(AlcanceDelToken.de(mapa("tipo", "todo"))).isEqualTo(new Alcance.Todo());
        assertThat(AlcanceDelToken.de(mapa("tipo", "opl"))).isEqualTo(new Alcance.Opl());
        assertThat(AlcanceDelToken.de(mapa("tipo", "ninguno"))).isEqualTo(new Alcance.Ninguno());
    }

    @Test
    @DisplayName("fuerza, regional, esm y equipo leen su dato")
    void conDato() {
        assertThat(AlcanceDelToken.de(mapa("tipo", "fuerza", "fuerza", "EJC"))).isEqualTo(new Alcance.Fuerza("EJC"));
        assertThat(AlcanceDelToken.de(mapa("tipo", "regional", "regional", 3L))).isEqualTo(new Alcance.Regional(3));
        assertThat(AlcanceDelToken.de(mapa("tipo", "esm", "punto", 42L))).isEqualTo(new Alcance.Esm(42));
        assertThat(AlcanceDelToken.de(mapa("tipo", "equipo", "auditor", 9L))).isEqualTo(new Alcance.Equipo(9));
    }

    @Test
    @DisplayName("los números llegan como Integer, Long o Double según el parser JSON: todos valen")
    void numerosDeCualquierTipo() {
        assertThat(AlcanceDelToken.de(mapa("tipo", "regional", "regional", 3))).isEqualTo(new Alcance.Regional(3));
        assertThat(AlcanceDelToken.de(mapa("tipo", "esm", "punto", 42))).isEqualTo(new Alcance.Esm(42));
        assertThat(AlcanceDelToken.de(mapa("tipo", "equipo", "auditor", 9.0))).isEqualTo(new Alcance.Equipo(9));
    }

    @Test
    @DisplayName("ida y vuelta: lo que emite seguridad para cada alcance se lee igual")
    void idaYVuelta() {
        List<Alcance> todos = List.of(new Alcance.Todo(), new Alcance.Opl(), new Alcance.Fuerza("ARC"), new Alcance.Regional(5),
                new Alcance.Esm(11), new Alcance.Equipo(21), new Alcance.Ninguno());
        for (Alcance a : todos) {
            Map<String, Object> emitido = switch (a) {
                case Alcance.Todo t -> mapa("tipo", "todo");
                case Alcance.Opl o -> mapa("tipo", "opl");
                case Alcance.Fuerza f -> mapa("tipo", "fuerza", "fuerza", f.codigo());
                case Alcance.Regional r -> mapa("tipo", "regional", "regional", r.id());
                case Alcance.Esm e -> mapa("tipo", "esm", "punto", e.puntoId());
                case Alcance.Equipo e -> mapa("tipo", "equipo", "auditor", e.usuarioId());
                case Alcance.Ninguno n -> mapa("tipo", "ninguno");
            };
            assertThat(AlcanceDelToken.de(emitido)).isEqualTo(a);
            assertThat(AlcanceDelToken.de(usuario(emitido))).isEqualTo(a);
        }
    }

    @Test
    @DisplayName("falla cerrado: null, vacío, sin tipo, tipo desconocido o que no es texto")
    void fallaCerrado() {
        Alcance ninguno = new Alcance.Ninguno();
        assertThat(AlcanceDelToken.de((Map<String, Object>) null)).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(Map.of())).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("fuerza", "EJC"))).as("sin tipo").isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "superusuario"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", ""))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", 5))).isEqualTo(ninguno);
        Map<String, Object> conNulo = new HashMap<>();
        conNulo.put("tipo", null);
        assertThat(AlcanceDelToken.de(conNulo)).isEqualTo(ninguno);
    }

    @Test
    @DisplayName("falla cerrado: el tipo exige su dato (ausente, nulo, en blanco o de otro tipo)")
    void faltaElDato() {
        Alcance ninguno = new Alcance.Ninguno();
        assertThat(AlcanceDelToken.de(mapa("tipo", "fuerza"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "fuerza", "fuerza", "  "))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "fuerza", "fuerza", 4))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "regional"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "regional", "regional", "abc"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "esm"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "esm", "punto", "x"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "equipo"))).isEqualTo(ninguno);
        assertThat(AlcanceDelToken.de(mapa("tipo", "equipo", "auditor", List.of(1)))).isEqualTo(ninguno);
    }

    @ParameterizedTest(name = "id {0} no es válido")
    @ValueSource(longs = {0, -1, -100})
    @DisplayName("falla cerrado: ids no positivos (Alcance.de del dominio también los rechaza)")
    void idsNoPositivos(long id) {
        assertThat(AlcanceDelToken.de(mapa("tipo", "regional", "regional", id))).isEqualTo(new Alcance.Ninguno());
        assertThat(AlcanceDelToken.de(mapa("tipo", "esm", "punto", id))).isEqualTo(new Alcance.Ninguno());
        assertThat(AlcanceDelToken.de(mapa("tipo", "equipo", "auditor", id))).isEqualTo(new Alcance.Ninguno());
    }

    @Test
    @DisplayName("UsuarioActual: toma su mapa de alcance; null o vacío = Ninguno")
    void deUsuarioActual() {
        assertThat(AlcanceDelToken.de(usuario(mapa("tipo", "fuerza", "fuerza", "FAC")))).isEqualTo(new Alcance.Fuerza("FAC"));
        assertThat(AlcanceDelToken.de(usuario(Map.of()))).isEqualTo(new Alcance.Ninguno());
        assertThat(AlcanceDelToken.de(usuario(null))).isEqualTo(new Alcance.Ninguno());
        assertThat(AlcanceDelToken.de((UsuarioActual) null)).isEqualTo(new Alcance.Ninguno());
    }
}
