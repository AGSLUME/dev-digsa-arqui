package co.com.agsamericas.digsa.visitas.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Alcance;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VA1.6: {@link AlcanceVisitas#condicion(Alcance)} contra datos reales (alias v = visitas, p = puntos). Escenario, con puntos nuevos:
 * punto A (EJC, regional R1): anual v1 (programada), necesidad programada v2, necesidad en ejecución v3.
 * punto B (ARC, regional R2): anual v4, necesidad notificada v5, necesidad reprogramada v6, necesidad cancelada v7.
 * Equipo: el auditor X está en v1 (líder) y v4 (apoyo).
 */
class AlcanceVisitasTest extends FixturaAgenda {

    long regionalA;
    long regionalB;
    long puntoA;
    long puntoB;
    long auditorX;
    long v1;
    long v2;
    long v3;
    long v4;
    long v5;
    long v6;
    long v7;

    @BeforeEach
    void escenario() {
        regionalA = sembrarRegional();
        regionalB = sembrarRegional();
        puntoA = sembrarPunto("EJC", regionalA);
        puntoB = sembrarPunto("ARC", regionalB);
        auditorX = sembrarAuditor("Auditor X", true);
        long otro = sembrarAuditor("Otro auditor", true);
        LocalDate f = LocalDate.of(2032, 5, 10);
        v1 = sembrarVisita("anual", puntoA, auditorX, f);
        v2 = sembrarVisita("necesidad", puntoA, otro, f);
        v3 = sembrarVisita("necesidad", puntoA, otro, f.plusDays(1));
        v4 = sembrarVisita("anual", puntoB, otro, f);
        v5 = sembrarVisita("necesidad", puntoB, otro, f.plusDays(2));
        v6 = sembrarVisita("necesidad", puntoB, otro, f.plusDays(3));
        v7 = sembrarVisita("necesidad", puntoB, otro, f.plusDays(4));
        repo.cambiarEstado(v3, Set.of("programada"), "ejecucion");
        repo.cambiarEstado(v5, Set.of("programada"), "notificada");
        repo.cambiarEstado(v6, Set.of("programada"), "reprogramada");
        repo.cambiarEstado(v7, Set.of("programada"), "cancelada");
        repo.reemplazarEquipo(v1, auditorX, List.of());
        repo.reemplazarEquipo(v4, otro, List.of(auditorX));
        repo.reemplazarEquipo(v2, otro, List.of());
    }

    /** Ids de MIS visitas que cumplen la condición del alcance. */
    private List<Long> visibles(Alcance a) {
        AlcanceVisitas.Condicion c = AlcanceVisitas.condicion(a);
        Map<String, Object> params = new LinkedHashMap<>(c.params());
        return jdbc.sql("SELECT v.id FROM visitas v JOIN puntos p ON p.id = v.punto_id WHERE (" + c.sql() + ") AND v.id IN (:mias) ORDER BY v.id")
                .params(params).param("mias", List.of(v1, v2, v3, v4, v5, v6, v7)).query(Long.class).list();
    }

    @Test
    @DisplayName("Todo: todas, también las necesidades pendientes y las canceladas")
    void todo() {
        assertThat(visibles(new Alcance.Todo())).containsExactly(v1, v2, v3, v4, v5, v6, v7);
    }

    @Test
    @DisplayName("Ninguno: ninguna")
    void ninguno() {
        assertThat(visibles(new Alcance.Ninguno())).isEmpty();
    }

    @Test
    @DisplayName("Opl: todo el país salvo la necesidad programada, notificada o reprogramada (ejecución y cancelada sí se ven)")
    void opl() {
        assertThat(visibles(new Alcance.Opl())).containsExactly(v1, v3, v4, v7);
    }

    @Test
    @DisplayName("Fuerza: solo puntos de esa fuerza, sin la regla de necesidad (Sanidad ve sus solicitudes)")
    void fuerza() {
        assertThat(visibles(new Alcance.Fuerza("EJC"))).containsExactly(v1, v2, v3);
        assertThat(visibles(new Alcance.Fuerza("ARC"))).containsExactly(v4, v5, v6, v7);
        assertThat(visibles(new Alcance.Fuerza("FAC"))).isEmpty();
    }

    @Test
    @DisplayName("Regional: solo puntos de la regional, y sin necesidad pendiente")
    void regional() {
        assertThat(visibles(new Alcance.Regional(regionalA))).containsExactly(v1, v3);
        assertThat(visibles(new Alcance.Regional(regionalB))).containsExactly(v4, v7);
        assertThat(visibles(new Alcance.Regional(-1))).isEmpty();
    }

    @Test
    @DisplayName("Esm: solo las del punto, y sin necesidad pendiente")
    void esm() {
        assertThat(visibles(new Alcance.Esm(puntoA))).containsExactly(v1, v3);
        assertThat(visibles(new Alcance.Esm(puntoB))).containsExactly(v4, v7);
    }

    @Test
    @DisplayName("Equipo: solo las visitas en que el usuario es del equipo (líder o apoyo)")
    void equipo() {
        assertThat(visibles(new Alcance.Equipo(auditorX))).containsExactly(v1, v4);
        assertThat(visibles(new Alcance.Equipo(nuevoUid()))).isEmpty();
    }

    @Test
    @DisplayName("el SQL y los parámetros son los del monolito (nombres alc_*)")
    void textoYParametros() {
        assertThat(AlcanceVisitas.condicion(new Alcance.Todo())).isEqualTo(new AlcanceVisitas.Condicion("true", Map.of()));
        assertThat(AlcanceVisitas.condicion(new Alcance.Ninguno())).isEqualTo(new AlcanceVisitas.Condicion("false", Map.of()));
        assertThat(AlcanceVisitas.condicion(new Alcance.Opl()).params()).isEmpty();
        assertThat(AlcanceVisitas.condicion(new Alcance.Opl()).sql())
                .isEqualTo("NOT (v.tipo = 'necesidad' AND v.estado IN ('programada', 'notificada', 'reprogramada'))");
        assertThat(AlcanceVisitas.condicion(new Alcance.Fuerza("EJC")).sql()).isEqualTo("p.fuerza = :alc_fuerza");
        assertThat(AlcanceVisitas.condicion(new Alcance.Fuerza("EJC")).params()).containsExactly(Map.entry("alc_fuerza", "EJC"));
        assertThat(AlcanceVisitas.condicion(new Alcance.Regional(3)).params()).containsExactly(Map.entry("alc_regional", 3L));
        assertThat(AlcanceVisitas.condicion(new Alcance.Esm(4)).params()).containsExactly(Map.entry("alc_punto", 4L));
        assertThat(AlcanceVisitas.condicion(new Alcance.Equipo(5)).params()).containsExactly(Map.entry("alc_usuario", 5L));
        assertThat(AlcanceVisitas.condicion(new Alcance.Regional(3)).sql()).startsWith("p.regional_id = :alc_regional AND NOT (");
        assertThat(AlcanceVisitas.condicion(new Alcance.Esm(4)).sql()).startsWith("v.punto_id = :alc_punto AND NOT (");
    }
}
