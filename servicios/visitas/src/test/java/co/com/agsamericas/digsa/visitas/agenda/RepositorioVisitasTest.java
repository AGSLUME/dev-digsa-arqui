package co.com.agsamericas.digsa.visitas.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VA1.6: {@link RepositorioVisitas} (port del del monolito) contra PostgreSQL real. Usuarios de {@code usuarios_copia}; datos con sufijo
 * único. SUPUESTO de firmas: iguales a las del monolito (records Visita, Miembro, Tipo, Punto, ComponenteChecklist con los mismos campos);
 * {@code reabrir(long)} devuelve boolean. Perfil Auditor = 3.
 */
class RepositorioVisitasTest extends FixturaAgenda {

    private static final LocalDate FECHA = LocalDate.of(2034, 6, 15);

    private long punto() {
        return sembrarPunto("EJC", null);
    }

    // ---------------------------------------------------------------- crear / número / porId

    @Test
    @DisplayName("crear asigna VS-AAAAMMDD-NNNN con la fecha PROGRAMADA y el id con 4 dígitos mínimo; no cambia al reprogramar")
    void numeroConLaFechaProgramada() {
        long lider = sembrarAuditor("Líder", true);
        long id = repo.crear("anual", punto(), FECHA, LocalTime.of(8, 30), lider, "Alcance", null, lider);

        var v = repo.porId(id).orElseThrow();
        assertThat(v.numero()).isEqualTo("VS-20340615-" + String.format("%04d", id));
        assertThat(v.numero()).matches("VS-20340615-\\d{4,}");
        assertThat(v.numero()).as("no es la fecha de creación").doesNotContain(
                OffsetDateTime.now(reloj).atZoneSameInstant(Fechas.BOGOTA).toLocalDate().toString().replace("-", ""));

        repo.reprogramar(id, Set.of("programada"), "reprogramada", FECHA.plusDays(20), LocalTime.of(10, 0));
        assertThat(repo.porId(id).orElseThrow().numero()).as("la reprogramación no cambia el número").isEqualTo(v.numero());
    }

    @Test
    @DisplayName("crear guarda los datos de la visita; el estado inicial es programada y se leen tipo y punto con él")
    void crearYPorId() {
        long lider = sembrarAuditor("Líder", true);
        long coord = sembrarUsuario("Coordinador", true, 2);
        long punto = punto();
        reloj.avanzar(Duration.ofMinutes(5));
        long id = repo.crear("necesidad", punto, FECHA, null, lider, "Revisar «ñandú» ✓", FECHA.minusDays(2), coord);

        var v = repo.porId(id).orElseThrow();
        assertThat(v.id()).isEqualTo(id);
        assertThat(v.tipo()).isEqualTo("necesidad");
        assertThat(v.aviso()).isEqualTo("no-opl");
        assertThat(v.puntoId()).isEqualTo(punto);
        assertThat(v.unidad()).isEqualTo("ESM");
        assertThat(v.fuerza()).isEqualTo("EJC");
        assertThat(v.modalidad()).isEqualTo("Dispensación");
        assertThat(v.exigeQf()).isFalse();
        assertThat(v.fecha()).isEqualTo(FECHA);
        assertThat(v.hora()).isNull();
        assertThat(v.auditorId()).isEqualTo(lider);
        assertThat(v.alcance()).isEqualTo("Revisar «ñandú» ✓");
        assertThat(v.estado()).isEqualTo("programada");
        assertThat(v.fechaSolicitud()).isEqualTo(FECHA.minusDays(2));
        assertThat(v.checklistVersion()).isNull();
        assertThat(v.equipoSinNotificar()).isFalse();
        assertThat(v.reaperturas()).isZero();
        assertThat(v.iniciada()).isNull();
        assertThat(v.origenId()).isNull();
        assertThat(v.seguimientoN()).isNull();
        assertThat(v.esSeguimiento()).isFalse();
        assertThat(jdbc.sql("SELECT creado_por FROM visitas WHERE id = :i").param("i", id).query(Long.class).single()).isEqualTo(coord);
        assertThat(repo.porId(-5)).isEmpty();
    }

    @Test
    @DisplayName("crear usa el Clock para creado y actualizado")
    void crearUsaElReloj() {
        long lider = sembrarAuditor("Líder", true);
        reloj.avanzar(Duration.ofDays(400));
        long id = repo.crear("anual", punto(), FECHA, null, lider, "A", null, lider);
        OffsetDateTime creado = jdbc.sql("SELECT creado FROM visitas WHERE id = :i").param("i", id).query(OffsetDateTime.class).single();
        assertThat(creado.toInstant()).isCloseTo(reloj.instant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS)); // PostgreSQL redondea a microsegundos
    }

    // ---------------------------------------------------------------- catálogos de apoyo

    @Test
    @DisplayName("tipo y punto: leen del catálogo; vacío si no existen")
    void tipoYPunto() {
        assertThat(repo.tipo("anual")).hasValueSatisfying(t -> {
            assertThat(t.codigo()).isEqualTo("anual");
            assertThat(t.nombre()).isEqualTo("Programada anual");
            assertThat(t.unidad()).isEqualTo("ESM");
            assertThat(t.modalidad()).isEqualTo("Dispensación");
            assertThat(t.aviso()).isEqualTo("opl");
        });
        assertThat(repo.tipo("no-existe")).isEmpty();

        long regional = sembrarRegional();
        long id = sembrarPunto("ARC", regional);
        assertThat(repo.punto(id)).hasValueSatisfying(p -> {
            assertThat(p.id()).isEqualTo(id);
            assertThat(p.unidad()).isEqualTo("ESM");
            assertThat(p.fuerza()).isEqualTo("ARC");
            assertThat(p.modalidad()).isEqualTo("Dispensación");
            assertThat(p.exigeQf()).isFalse();
            assertThat(p.activo()).isTrue();
            assertThat(p.regionalId()).isEqualTo(regional);
        });
        assertThat(repo.punto(sembrarPunto("ARC", null))).hasValueSatisfying(p -> assertThat(p.regionalId()).isNull());
        assertThat(repo.punto(-1)).isEmpty();
    }

    @Test
    @DisplayName("auditoresActivos: activo con perfil 3, con su rol técnico (otro por defecto); excluye inactivos, sin perfil e inexistentes")
    void auditoresActivos() {
        long conRol = sembrarAuditor("Con rol", true);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:u, 'quimico_farmaceutico')").param("u", conRol).update();
        long sinRol = sembrarAuditor("Sin rol", true);
        long mixto = sembrarUsuario("Coordinador y auditor", true, 2, 3);
        long inactivo = sembrarAuditor("Inactivo", false);
        long coordinador = sembrarUsuario("Solo coordinador", true, 2);

        Map<Long, String> r = repo.auditoresActivos(List.of(conRol, sinRol, mixto, inactivo, coordinador, nuevoUid()));
        assertThat(r).containsOnlyKeys(conRol, sinRol, mixto);
        assertThat(r).containsEntry(conRol, "quimico_farmaceutico").containsEntry(sinRol, "otro").containsEntry(mixto, "otro");
        assertThat(repo.auditoresActivos(List.of())).isEmpty();
    }

    @Test
    @DisplayName("versionVigente y componentes: la vigente del checklist con sus ítems en orden")
    void checklist() {
        String vigente = jdbc.sql("SELECT version FROM chk_versiones WHERE vigente").query(String.class).single();
        assertThat(repo.versionVigente()).contains(vigente);
        var comps = repo.componentes(vigente);
        assertThat(comps).isNotEmpty();
        assertThat(comps).extracting(RepositorioVisitas.ComponenteChecklist::codigo).contains("C00", "C01").doesNotHaveDuplicates();
        var c01 = comps.stream().filter(c -> c.codigo().equals("C01")).findFirst().orElseThrow();
        assertThat(c01.nombre()).isEqualTo("Seguimiento a la operación del OPL");
        assertThat(c01.items()).isNotEmpty().startsWith("SO-01");
        assertThat(c01.unidades()).isNotEmpty();
        assertThat(repo.componentes("v-inexistente")).isEmpty();
    }

    // ---------------------------------------------------------------- seguimiento

    @Test
    @DisplayName("marcarSeguimiento y seguimientoDe: el vigente (no cancelado) de la visita de origen")
    void seguimiento() {
        long lider = sembrarAuditor("Líder", true);
        long punto = punto();
        long origen = repo.crear("anual", punto, FECHA, null, lider, "A", null, lider);
        long plan = repo.crear("plan", punto, FECHA.plusDays(30), null, lider, "A", null, lider);
        assertThat(repo.seguimientoDe(origen)).isEmpty();

        repo.marcarSeguimiento(plan, origen, 1, "v6");
        var s = repo.seguimientoDe(origen).orElseThrow();
        assertThat(s.id()).isEqualTo(plan);
        assertThat(s.origenId()).isEqualTo(origen);
        assertThat(s.seguimientoN()).isEqualTo(1);
        assertThat(s.checklistVersion()).isEqualTo("v6");
        assertThat(s.esSeguimiento()).isTrue();

        repo.cambiarEstado(plan, Set.of("programada"), "cancelada");
        assertThat(repo.seguimientoDe(origen)).as("el cancelado no cuenta").isEmpty();
    }

    // ---------------------------------------------------------------- escrituras condicionadas

    @Test
    @DisplayName("actualizar cambia los datos de la visita y la fecha de actualización; no toca el estado ni el número")
    void actualizar() {
        long lider = sembrarAuditor("Líder", true);
        long otroLider = sembrarAuditor("Otro líder", true);
        long id = repo.crear("anual", punto(), FECHA, null, lider, "A", null, lider);
        String numero = repo.porId(id).orElseThrow().numero();
        long puntoNuevo = punto();
        repo.actualizar(id, "riesgo", puntoNuevo, FECHA.plusDays(3), LocalTime.of(14, 15), otroLider, "Nuevo alcance", null);
        var v = repo.porId(id).orElseThrow();
        assertThat(v.tipo()).isEqualTo("riesgo");
        assertThat(v.puntoId()).isEqualTo(puntoNuevo);
        assertThat(v.fecha()).isEqualTo(FECHA.plusDays(3));
        assertThat(v.hora()).isEqualTo(LocalTime.of(14, 15));
        assertThat(v.auditorId()).isEqualTo(otroLider);
        assertThat(v.alcance()).isEqualTo("Nuevo alcance");
        assertThat(v.estado()).isEqualTo("programada");
        assertThat(v.numero()).isEqualTo(numero);
    }

    @Test
    @DisplayName("cambiarEstado: solo si el estado actual está entre los de origen; devuelve si cambió")
    void cambiarEstado() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        assertThat(repo.cambiarEstado(id, Set.of("notificada"), "ejecucion")).isFalse();
        assertThat(repo.porId(id).orElseThrow().estado()).isEqualTo("programada");
        assertThat(repo.cambiarEstado(id, Set.of("programada", "reprogramada"), "notificada")).isTrue();
        assertThat(repo.porId(id).orElseThrow().estado()).isEqualTo("notificada");
        assertThat(repo.cambiarEstado(-1, Set.of("programada"), "notificada")).isFalse();
    }

    @Test
    @DisplayName("reprogramar: condicionado al estado; cambia estado, fecha y hora")
    void reprogramar() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        assertThat(repo.reprogramar(id, Set.of("cerrada"), "reprogramada", FECHA.plusDays(5), LocalTime.NOON)).isFalse();
        assertThat(repo.reprogramar(id, Set.of("programada"), "reprogramada", FECHA.plusDays(5), LocalTime.NOON)).isTrue();
        var v = repo.porId(id).orElseThrow();
        assertThat(v.estado()).isEqualTo("reprogramada");
        assertThat(v.fecha()).isEqualTo(FECHA.plusDays(5));
        assertThat(v.hora()).isEqualTo(LocalTime.NOON);
    }

    @Test
    @DisplayName("iniciar: pasa a ejecución con la versión del checklist y la hora de inicio; condicionado al estado")
    void iniciar() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        assertThat(repo.iniciar(id, Set.of("notificada"), "v6")).isFalse();
        assertThat(repo.porId(id).orElseThrow().iniciada()).isNull();
        reloj.avanzar(Duration.ofHours(2));
        assertThat(repo.iniciar(id, Set.of("programada", "notificada"), "v6")).isTrue();
        var v = repo.porId(id).orElseThrow();
        assertThat(v.estado()).isEqualTo("ejecucion");
        assertThat(v.checklistVersion()).isEqualTo("v6");
        assertThat(v.iniciada().toInstant()).isCloseTo(reloj.instant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("reabrir: solo cerrada → ejecución, suma una reapertura; en otro estado devuelve false y no cambia nada")
    void reabrir() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        assertThat(repo.reabrir(id)).as("programada no se reabre").isFalse();
        repo.iniciar(id, Set.of("programada"), "v6");
        assertThat(repo.reabrir(id)).as("en ejecución no se reabre").isFalse();
        assertThat(repo.porId(id).orElseThrow().reaperturas()).isZero();

        assertThat(repo.cambiarEstado(id, Set.of("ejecucion"), "cerrada")).isTrue();
        assertThat(repo.reabrir(id)).isTrue();
        var v = repo.porId(id).orElseThrow();
        assertThat(v.estado()).isEqualTo("ejecucion");
        assertThat(v.reaperturas()).isEqualTo(1);

        repo.cambiarEstado(id, Set.of("ejecucion"), "cerrada");
        assertThat(repo.reabrir(id)).isTrue();
        assertThat(repo.porId(id).orElseThrow().reaperturas()).as("acumula").isEqualTo(2);
        assertThat(repo.reabrir(-1)).isFalse();
    }

    @Test
    @DisplayName("marcarEquipoSinNotificar y cambiarLider")
    void banderaYLider() {
        long lider = sembrarAuditor("Líder", true);
        long otro = sembrarAuditor("Otro", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        repo.marcarEquipoSinNotificar(id, true);
        assertThat(repo.porId(id).orElseThrow().equipoSinNotificar()).isTrue();
        repo.marcarEquipoSinNotificar(id, false);
        assertThat(repo.porId(id).orElseThrow().equipoSinNotificar()).isFalse();
        repo.cambiarLider(id, otro);
        assertThat(repo.porId(id).orElseThrow().auditorId()).isEqualTo(otro);
    }

    // ---------------------------------------------------------------- componentes, equipo y reparto

    @Test
    @DisplayName("componentes: reemplazar deja exactamente los dados, ordenados; vacío los quita")
    void componentes() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        assertThat(repo.componentesDe(id)).isEmpty();
        repo.reemplazarComponentes(id, List.of("C03", "C01", "C02"));
        assertThat(repo.componentesDe(id)).containsExactly("C01", "C02", "C03");
        repo.reemplazarComponentes(id, List.of("C05"));
        assertThat(repo.componentesDe(id)).containsExactly("C05");
        repo.reemplazarComponentes(id, List.of());
        assertThat(repo.componentesDe(id)).isEmpty();
    }

    @Test
    @DisplayName("equipo: líder con orden 0 y apoyos 1…N, con datos de usuarios_copia y el rol técnico; esDelEquipo")
    void equipo() {
        long lider = sembrarAuditor("Líder Ñ", true);
        long a1 = sembrarAuditor("Apoyo uno", true);
        long a2 = sembrarAuditor("Apoyo dos", false);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:u, 'regente')").param("u", a1).update();
        long fuera = sembrarAuditor("Fuera", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);

        repo.reemplazarEquipo(id, lider, List.of(a1, a2));
        var eq = repo.equipo(id);
        assertThat(eq).extracting(RepositorioVisitas.Miembro::id).containsExactly(lider, a1, a2);
        assertThat(eq).extracting(RepositorioVisitas.Miembro::funcion).containsExactly("lider", "apoyo", "apoyo");
        assertThat(eq).extracting(RepositorioVisitas.Miembro::orden).containsExactly(0, 1, 2);
        assertThat(eq).extracting(RepositorioVisitas.Miembro::rol).containsExactly("otro", "regente", "otro");
        assertThat(eq).extracting(RepositorioVisitas.Miembro::activo).containsExactly(true, true, false);
        var m = eq.get(0);
        assertThat(m.nombre()).isEqualTo("Líder Ñ");
        assertThat(m.usuario()).isEqualTo("u" + lider);
        assertThat(m.documento()).isEqualTo(documentoDe(lider));
        assertThat(m.correo()).isEqualTo("u" + lider + "@visitas-a.prueba.local");

        assertThat(repo.esDelEquipo(id, a1)).isTrue();
        assertThat(repo.esDelEquipo(id, fuera)).isFalse();

        repo.reemplazarEquipo(id, a1, List.of());
        assertThat(repo.equipo(id)).extracting(RepositorioVisitas.Miembro::id).containsExactly(a1);
        assertThat(repo.esDelEquipo(id, lider)).isFalse();
    }

    @Test
    @DisplayName("reparto: reemplazar, leer, mover una parte y partesDe; reemplazar el equipo borra el reparto")
    void reparto() {
        long lider = sembrarAuditor("Líder", true);
        long apoyo = sembrarAuditor("Apoyo", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        repo.reemplazarEquipo(id, lider, List.of(apoyo));

        repo.reemplazarReparto(id, Map.of("C01", lider, "C02", apoyo, "OBL", lider), Map.of("C02", "coord1"));
        assertThat(repo.reparto(id)).containsExactly(Map.entry("C01", lider), Map.entry("C02", apoyo), Map.entry("OBL", lider));
        assertThat(jdbc.sql("SELECT asignado_por FROM visitas_reparto WHERE visita_id = :v AND parte = 'C02'").param("v", id).query(String.class).single())
                .isEqualTo("coord1");
        assertThat(jdbc.sql("SELECT asignado_por FROM visitas_reparto WHERE visita_id = :v AND parte = 'C01'").param("v", id).query(String.class).single())
                .as("sin dato: sistema").isEqualTo("sistema");
        assertThat(repo.partesDe(id, lider)).containsExactly("C01", "OBL");
        assertThat(repo.partesDe(id, apoyo)).containsExactly("C02");

        assertThat(repo.moverParte(id, "C01", apoyo, "coord2")).isTrue();
        assertThat(repo.partesDe(id, apoyo)).containsExactly("C01", "C02");
        assertThat(repo.moverParte(id, "C09", apoyo, "coord2")).as("parte que no existe").isFalse();

        repo.reemplazarReparto(id, Map.of("C05", apoyo), Map.of());
        assertThat(repo.reparto(id)).containsExactly(Map.entry("C05", apoyo));

        repo.reemplazarEquipo(id, lider, List.of());
        assertThat(repo.reparto(id)).as("cada parte es de un integrante: el reparto se borra con el equipo").isEmpty();
    }

    // ---------------------------------------------------------------- historial

    @Test
    @DisplayName("historial: guarda usuario (o el sistema con null) y devuelve en orden de inserción")
    void historial() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        repo.historial(id, lider, "u" + lider, "visita.creada", "Detalle uno");
        repo.historial(id, null, null, "visita.notificada", null);
        var h = repo.historialDe(id);
        assertThat(h).hasSize(2);
        assertThat(h.get(0)).containsEntry("usuario", "u" + lider).containsEntry("evento", "visita.creada").containsEntry("detalle", "Detalle uno");
        assertThat(h.get(1).get("usuario")).isNull();
        assertThat(h.get(1).get("detalle")).isNull();
        assertThat(h.get(1)).containsEntry("evento", "visita.notificada");
        assertThat(jdbc.sql("SELECT usuario_id FROM visitas_historial WHERE visita_id = :v ORDER BY id").param("v", id).query(Long.class).list())
                .containsExactly(lider, null);
    }

    @Test
    @DisplayName("historial corta el evento a 60 y el detalle a 1000 caracteres (con «…» al final), sin cortar emojis por la mitad")
    void historialCorta() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        repo.historial(id, lider, "u", "e".repeat(100), "d".repeat(1500));
        repo.historial(id, lider, "u", "😀".repeat(70), "😀".repeat(1200));
        repo.historial(id, lider, "u", "e".repeat(60), "d".repeat(1000));
        var h = repo.historialDe(id);

        String ev = (String) h.get(0).get("evento");
        String det = (String) h.get(0).get("detalle");
        assertThat(ev.codePointCount(0, ev.length())).isEqualTo(60);
        assertThat(ev).isEqualTo("e".repeat(59) + "…");
        assertThat(det.codePointCount(0, det.length())).isEqualTo(1000);
        assertThat(det).isEqualTo("d".repeat(999) + "…");

        String ev2 = (String) h.get(1).get("evento");
        assertThat(ev2).isEqualTo("😀".repeat(59) + "…");
        assertThat(((String) h.get(1).get("detalle")).codePointCount(0, ((String) h.get(1).get("detalle")).length())).isEqualTo(1000);

        assertThat(h.get(2).get("evento")).as("justo en el límite no se corta").isEqualTo("e".repeat(60));
        assertThat(h.get(2).get("detalle")).isEqualTo("d".repeat(1000));
    }

    @Test
    @DisplayName("historial usa el Clock para la fecha")
    void historialUsaElReloj() {
        long lider = sembrarAuditor("Líder", true);
        long id = sembrarVisita("anual", punto(), lider, FECHA);
        reloj.avanzar(Duration.ofDays(30));
        repo.historial(id, lider, "u", "evento", null);
        OffsetDateTime f = jdbc.sql("SELECT fecha FROM visitas_historial WHERE visita_id = :v").param("v", id).query(OffsetDateTime.class).single();
        assertThat(f.toInstant()).isCloseTo(reloj.instant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }
}
