package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * S2.2: roles adicionales parametrizables (Anexo 5, 1.12; {@code /api/usuarios/roles}) con perfil base y subconjunto de pantallas, y el
 * Analista con consulta nacional. Portado de {@code RolesApiTest} del monolito. Los permisos y la sesión se leen de la base.
 */
class RolesApiTest extends AdministracionApiBase {

    private static Map<String, Object> rol(String nombre, int base, List<String> pantallas) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", nombre);
        m.put("descripcion", "Rol de prueba");
        m.put("baseId", base);
        m.put("pantallas", pantallas);
        return m;
    }

    private int crearRol(String nombre, int base, List<String> pantallas) throws Exception {
        ResultActions r = publicar("/api/usuarios/roles", rol(nombre, base, pantallas)).andExpect(status().isCreated());
        return json(r).path("id").asInt();
    }

    /** Usuario con contraseña lista que tiene solo el rol adicional (la asignación va directo a la base de prueba). */
    private DatosPrueba.Ficticio usuarioConRol(int rolId) {
        var u = datos.usuario(Perfil.COORDINADOR);
        jdbc.sql("DELETE FROM usuarios_perfiles WHERE usuario_id = :u").param("u", u.id()).update();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", u.id()).param("p", rolId).update();
        return u;
    }

    private static List<String> rutas(JsonNode sesion, String campo) {
        List<String> r = new ArrayList<>();
        sesion.path(campo).forEach(x -> r.add(x.isString() ? x.asString() : x.path("ruta").asString()));
        return r;
    }

    private JsonNode sesion(RequestPostProcessor quien) throws Exception {
        return json(leer(quien, "/api/sesion").andExpect(status().isOk()));
    }

    // ---------- Catálogo y alta ----------

    @Test
    @DisplayName("Contrato catalogoRoles: los perfiles base elegibles (no Administrador ni Auditor) con sus pantallas visibles, y los roles con sus usuarios activos")
    void catalogo() throws Exception {
        int id = crearRol(unico("catalogo"), Perfil.DIGSA.id(), List.of("CALENDARIO", "REPORTES"));
        usuarioConRol(id);
        var inactivo = datos.nuevo().inactivo().crear();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", inactivo.id()).param("p", id).update();

        JsonNode cat = json(leer("/api/usuarios/roles").andExpect(status().isOk()));
        List<Integer> bases = new ArrayList<>();
        for (JsonNode b : cat.path("bases")) {
            bases.add(b.path("id").asInt());
            assertThat(b.path("pantallas").size()).as("pantallas de " + b.path("nombre").asString()).isPositive();
            List<String> codigos = new ArrayList<>();
            b.path("pantallas").forEach(p -> codigos.add(p.path("codigo").asString()));
            assertThat(codigos).as("la pantalla oculta y las del Administrador no se ofrecen").doesNotContain("VISITA", "USUARIOS", "LOGS");
        }
        assertThat(bases).containsExactlyInAnyOrder(2, 5, 6, 7, 8, 9, 10);
        JsonNode mio = null;
        for (JsonNode r : cat.path("roles")) {
            if (r.path("id").asInt() == id) {
                mio = r;
            }
        }
        assertThat(mio).isNotNull();
        assertThat(mio.path("baseId").asInt()).isEqualTo(Perfil.DIGSA.id());
        assertThat(mio.path("activo").asBoolean()).isTrue();
        assertThat(mio.path("usuarios").asLong()).as("solo cuentan los usuarios activos").isEqualTo(1);
        assertThat(mio.path("pantallas").toString()).isEqualTo("[\"CALENDARIO\",\"REPORTES\"]");
    }

    @Test
    @DisplayName("Contrato crearRol: 201 «Rol creado. Asígnelo en el formulario de cada usuario.»; id desde 100, con su base y sus pantallas en la base")
    void crear() throws Exception {
        String nombre = unico("Gestor del riesgo ");
        publicar("/api/usuarios/roles", rol(nombre, Perfil.COORDINADOR.id(), List.of("RIESGO", "DASHBOARD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mensaje").value("Rol creado. Asígnelo en el formulario de cada usuario."))
                .andExpect(jsonPath("$.id").isNumber());
        int id = jdbc.sql("SELECT id FROM perfiles WHERE nombre = :n").param("n", nombre).query(Integer.class).single();
        assertThat(id).isGreaterThanOrEqualTo(100);
        assertThat(jdbc.sql("SELECT base_id FROM perfiles WHERE id = :id").param("id", id).query(Integer.class).single()).isEqualTo(Perfil.COORDINADOR.id());
        assertThat(jdbc.sql("SELECT activo FROM perfiles WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT pantalla FROM perfiles_pantallas WHERE perfil_id = :id ORDER BY pantalla").param("id", id).query(String.class).list())
                .containsExactly("DASHBOARD", "RIESGO");
    }

    @Test
    @DisplayName("Concurrencia: dos altas simultáneas del mismo rol: una 201 y la otra 422, no 500; queda una sola fila")
    void altasSimultaneasDelMismoRol() throws Exception {
        String nombre = unico("Simultaneo ");
        Map<String, Object> cuerpo = rol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> estado(cuerpo));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> estado(cuerpo));
        assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 422);
        assertThat(contar("SELECT count(*) FROM perfiles WHERE lower(nombre) = ?", nombre.toLowerCase())).isEqualTo(1);
    }

    private int estado(Map<String, Object> cuerpo) {
        try {
            return publicar("/api/usuarios/roles", cuerpo).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- El rol limita las pantallas ----------

    @Test
    @DisplayName("RolesApiTest:57 rol con base Coordinador y solo Riesgo y Dashboard: menú y pantallas limitados; no entra a la administración; muestra el rol, no la base")
    void rolLimitaPantallas() throws Exception {
        String nombre = unico("Gestor del riesgo ");
        int id = crearRol(nombre, Perfil.COORDINADOR.id(), List.of("RIESGO", "DASHBOARD"));
        assertThat(id).isGreaterThanOrEqualTo(100);

        var u = usuarioConRol(id);
        var quien = sesionDe(u);
        JsonNode s = sesion(quien);
        assertThat(rutas(s, "menu")).containsExactlyInAnyOrder("inicio", "riesgo", "dashboard");
        assertThat(rutas(s, "pantallas")).containsExactlyInAnyOrder("inicio", "riesgo", "dashboard");
        assertThat(rutas(s, "menu")).as("agenda es del coordinador pero no del rol").doesNotContain("agenda", "maestros", "usuarios");
        // Falla cerrado: el rol no da la identidad de su base; la administración sigue siendo solo del Administrador
        leer(quien, "/api/usuarios").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        leer(quien, "/api/usuarios/roles").andExpect(status().isForbidden());
        assertThat(s.path("perfiles").toString()).as("muestra el rol, no la base").contains(nombre).doesNotContain("Coordinador");
    }

    @Test
    @DisplayName("RolesApiTest:86 un rol no se mezcla con perfiles directos ni con roles de otra base; el Auditor no es base; una pantalla oculta no se ofrece")
    void mezclas() throws Exception {
        int gestor = crearRol(unico("Gestor mezcla "), Perfil.COORDINADOR.id(), List.of("RIESGO"));
        int lectura = crearRol(unico("Lectura mezcla "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        Map<String, Object> d = datosUsuario(unico("mezcla"), Perfil.AUDITOR.id(), gestor);
        assertThat(mensaje(publicar("/api/usuarios", d))).contains("no tiene además perfiles");
        d.put("perfiles", List.of(gestor, lectura));
        assertThat(mensaje(publicar("/api/usuarios", d))).contains("mismo perfil base");
        publicar("/api/usuarios/roles", rol(unico("Auditor parcial "), Perfil.AUDITOR.id(), List.of("MIS_VISITAS")))
                .andExpect(status().isUnprocessableEntity());
        publicar("/api/usuarios/roles", rol(unico("Visita sola "), Perfil.COORDINADOR.id(), List.of("VISITA")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(contar("SELECT count(*) FROM usuarios WHERE usuario = ?", d.get("usuario"))).isZero();
    }

    // ---------- Validaciones ----------

    @Test
    @DisplayName("RolesApiTest:108 validaciones: base Administrador, pantalla ajena a la base, sin pantallas, pantalla inexistente y nombre repetido, con los textos del monolito")
    void validaciones() throws Exception {
        publicar("/api/usuarios/roles", rol(unico("Raro "), Perfil.ADMINISTRADOR.id(), List.of("USUARIOS"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.mensaje").value("El perfil base no es válido: un rol adicional no deriva del Administrador ni del Auditor."));
        assertThat(mensaje(publicar("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of("AGENDA")))))
                .isEqualTo("La pantalla «Agenda de visitas» no es del perfil base DIGSA.");
        assertThat(mensaje(publicar("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of()))))
                .isEqualTo("Marque al menos una pantalla para el rol.");
        assertThat(mensaje(publicar("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of("NO_EXISTE")))))
                .isEqualTo("Hay una pantalla no válida.");
        assertThat(mensaje(publicar("/api/usuarios/roles", rol("auditor", Perfil.DIGSA.id(), List.of("CALENDARIO")))))
                .as("el nombre de un perfil base, con otra grafía").isEqualTo("Ya existe un perfil o rol con ese nombre.");
        String nombre = unico("Repetido ");
        crearRol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        assertThat(mensaje(publicar("/api/usuarios/roles", rol(nombre.toUpperCase(), Perfil.DIGSA.id(), List.of("CALENDARIO")))))
                .as("sin distinguir mayúsculas").isEqualTo("Ya existe un perfil o rol con ese nombre.");
    }

    @Test
    @DisplayName("Revalidación en el servidor: nombre fuera de 3 a 60, descripción de más de 200 y cuerpo sin base ni pantallas")
    void validacionesDeCampos() throws Exception {
        assertThat(mensaje(publicar("/api/usuarios/roles", rol("ab", Perfil.DIGSA.id(), List.of("CALENDARIO")))))
                .isEqualTo("El nombre del rol debe tener de 3 a 60 caracteres.");
        assertThat(mensaje(publicar("/api/usuarios/roles", rol("n".repeat(61), Perfil.DIGSA.id(), List.of("CALENDARIO")))))
                .isEqualTo("El nombre del rol debe tener de 3 a 60 caracteres.");
        Map<String, Object> larga = rol(unico("Larga "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        larga.put("descripcion", "d".repeat(201));
        assertThat(mensaje(publicar("/api/usuarios/roles", larga))).isEqualTo("La descripción tiene máximo 200 caracteres.");
        Map<String, Object> limite = rol(unico("Limite "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        limite.put("descripcion", "d".repeat(200));
        publicar("/api/usuarios/roles", limite).andExpect(status().isCreated());
        publicar("/api/usuarios/roles", Map.of()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("RolesApiTest:108 solo el administrador: el Coordinador recibe 403 en todas las operaciones de roles y no se crea nada")
    void soloAdministrador() throws Exception {
        var quien = sesionDe(datos.usuario(Perfil.COORDINADOR));
        int id = crearRol(unico("Existente "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        leer(quien, "/api/usuarios/roles").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        String nombre = unico("Ajeno ");
        publicar(quien, "/api/usuarios/roles", rol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"))).andExpect(status().isForbidden());
        poner(quien, "/api/usuarios/roles/" + id, rol(unico("Otro "), Perfil.DIGSA.id(), List.of("REPORTES"))).andExpect(status().isForbidden());
        poner(quien, "/api/usuarios/roles/" + id + "/estado", Map.of("activo", false)).andExpect(status().isForbidden());
        poner(quien, "/api/usuarios/perfiles/10", Map.of("nombre", "Otro nombre")).andExpect(status().isForbidden());
        assertThat(contar("SELECT count(*) FROM perfiles WHERE nombre = ?", nombre)).isZero();
        assertThat(jdbc.sql("SELECT activo FROM perfiles WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT nombre FROM perfiles WHERE id = 10").query(String.class).single()).isEqualTo("Analista");
    }

    // ---------- Editar, inactivar, asignar ----------

    @Test
    @DisplayName("RolesApiTest:127 editar el rol: cambia nombre y pantallas; la base no se cambia; un rol que no existe es 404")
    void editar() throws Exception {
        String nombre = unico("Lectura fuerza ");
        int id = crearRol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        poner("/api/usuarios/roles/" + id, rol(nombre + "b", Perfil.DIGSA.id(), List.of("CALENDARIO", "REPORTES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Rol actualizado. Aplica desde la siguiente acción de sus usuarios."));
        assertThat(jdbc.sql("SELECT nombre FROM perfiles WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo(nombre + "b");
        assertThat(jdbc.sql("SELECT pantalla FROM perfiles_pantallas WHERE perfil_id = :id ORDER BY pantalla").param("id", id).query(String.class).list())
                .containsExactly("CALENDARIO", "REPORTES");

        assertThat(mensaje(poner("/api/usuarios/roles/" + id, rol(nombre + "b", Perfil.SANIDAD.id(), List.of("CALENDARIO")))))
                .isEqualTo("El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.");
        assertThat(jdbc.sql("SELECT base_id FROM perfiles WHERE id = :id").param("id", id).query(Integer.class).single()).isEqualTo(Perfil.DIGSA.id());

        JsonNode cat = json(leer("/api/usuarios/roles"));
        boolean visto = false;
        for (JsonNode x : cat.path("roles")) {
            if (x.path("id").asInt() == id) {
                visto = true;
                assertThat(x.path("pantallas").toString()).isEqualTo("[\"CALENDARIO\",\"REPORTES\"]");
            }
        }
        assertThat(visto).isTrue();
        poner("/api/usuarios/roles/99999", rol(unico("Nadie "), Perfil.DIGSA.id(), List.of("CALENDARIO"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El rol no existe."));
    }

    @Test
    @DisplayName("Contrato editarRol: el nombre no puede repetir el de otro perfil o rol, pero sí conservar el propio")
    void editarNombreRepetido() throws Exception {
        String a = unico("Rol a ");
        String b = unico("Rol b ");
        int idA = crearRol(a, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        crearRol(b, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        assertThat(mensaje(poner("/api/usuarios/roles/" + idA, rol(b, Perfil.DIGSA.id(), List.of("CALENDARIO")))))
                .isEqualTo("Ya existe un perfil o rol con ese nombre.");
        poner("/api/usuarios/roles/" + idA, rol(a, Perfil.DIGSA.id(), List.of("CALENDARIO", "DASHBOARD"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("RolesApiTest:152 inactivar el rol: sus usuarios dejan de ver sus pantallas desde la siguiente petición; reactivarlo no revive una mezcla no válida; un perfil base no es un rol (404)")
    void inactivar() throws Exception {
        int id = crearRol(unico("Temporal "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        var u = usuarioConRol(id);
        var quien = sesionDe(u);
        assertThat(rutas(sesion(quien), "menu")).contains("calendario");

        poner("/api/usuarios/roles/" + id + "/estado", Map.of("activo", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Rol inactivado (1 usuarios activos lo tienen)."));
        assertThat(rutas(sesion(quien), "menu")).as("desde la siguiente petición, sin volver a ingresar").doesNotContain("calendario");

        // Editar al usuario con el rol inactivo no borra esa asignación (al reactivar el rol vuelve a contar)...
        Map<String, Object> d = datosUsuario(u.usuario(), Perfil.AUDITOR.id());
        d.put("correo", u.correo());
        d.put("documento", "80777666");
        poner("/api/usuarios/" + u.id(), d).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM usuarios_perfiles WHERE usuario_id = ? AND perfil_id = ?", u.id(), id))
                .as("se conserva la asignación al rol inactivo").isEqualTo(1);
        // ...pero si al reactivarlo queda una mezcla no válida (Auditor directo + rol), el rol no suma nada: falla cerrado
        poner("/api/usuarios/roles/" + id + "/estado", Map.of("activo", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Rol activado (1 usuarios activos lo tienen)."));
        var nueva = sesionDe(u);
        assertThat(rutas(sesion(nueva), "menu")).contains("mis-visitas").doesNotContain("calendario");

        poner("/api/usuarios/roles/2/estado", Map.of("activo", false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El rol no existe."));
        assertThat(jdbc.sql("SELECT activo FROM perfiles WHERE id = 2").query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("RolesApiTest:187 el administrador asigna el rol en Usuarios; con base ESM exige el establecimiento; las combinaciones se validan con la base")
    void asignarEnUsuarios() throws Exception {
        int esm = crearRol(unico("Jefe de farmacia "), Perfil.ESM.id(), List.of("CALENDARIO", "DASHBOARD"));
        String usuario = unico("jefe");
        Map<String, Object> d = datosUsuario(usuario, esm);
        assertThat(mensaje(publicar("/api/usuarios", d))).isEqualTo("El perfil ESM necesita el establecimiento (ESM activo).");
        d.put("perfiles", List.of(esm, Perfil.DIGSA.id()));
        assertThat(mensaje(publicar("/api/usuarios", d))).contains("no tiene además perfiles");
        d.put("perfiles", List.of(esm));
        long punto = datos.puntoEsm("ESM del rol", "EJC", true);
        d.put("alcancePunto", punto);
        long id = json(publicar("/api/usuarios", d).andExpect(status().isCreated())).path("id").asLong();

        JsonNode lista = json(leer("/api/usuarios"));
        boolean visto = false;
        for (JsonNode x : lista.path("usuarios")) {
            if (x.path("id").asLong() == id) {
                visto = true;
                assertThat(x.path("perfiles").toString()).isEqualTo("[" + esm + "]");
                assertThat(x.path("alcancePunto").asLong()).isEqualTo(punto);
            }
        }
        assertThat(visto).isTrue();
        JsonNode asignable = null;
        for (JsonNode p : lista.path("perfiles")) {
            if (p.path("id").asInt() == esm) {
                asignable = p;
            }
        }
        assertThat(asignable).as("el rol activo se ofrece en el formulario").isNotNull();
        assertThat(asignable.path("baseId").asInt()).isEqualTo(Perfil.ESM.id());
    }

    @Test
    @DisplayName("Diseño S2: un rol inactivo no se ofrece ni se acepta al crear usuarios («Hay un perfil no válido.»)")
    void rolInactivoNoSeAsigna() throws Exception {
        int id = crearRol(unico("Apagado "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        poner("/api/usuarios/roles/" + id + "/estado", Map.of("activo", false)).andExpect(status().isOk());
        assertThat(mensaje(publicar("/api/usuarios", datosUsuario(unico("conapagado"), id)))).isEqualTo("Hay un perfil no válido.");
        List<Integer> ofrecidos = new ArrayList<>();
        json(leer("/api/usuarios")).path("perfiles").forEach(p -> ofrecidos.add(p.path("id").asInt()));
        assertThat(ofrecidos).doesNotContain(id);
    }

    // ---------- Analista ----------

    @Test
    @DisplayName("RolesApiTest:216 Analista (ítem 3): consulta nacional como la DIGSA; no solicita visitas, no agenda y no administra")
    void analista() throws Exception {
        var u = datos.usuario(Perfil.ANALISTA);
        var quien = sesionDe(u);
        JsonNode s = sesion(quien);
        assertThat(rutas(s, "menu")).contains("calendario", "dashboard", "riesgo", "reportes", "hallazgos")
                .doesNotContain("agenda", "solicitudes", "usuarios", "logs", "opl");
        assertThat(s.path("perfiles").toString()).contains("Analista");
        leer(quien, "/api/usuarios").andExpect(status().isForbidden()).andExpect(jsonPath("$.mensaje").value(SIN_PERMISO));
        leer(quien, "/api/usuarios/roles").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RolesApiTest:216 el Analista se puede crear desde Usuarios (perfil 10) y queda como único perfil")
    void analistaSeCreaPorLaApi() throws Exception {
        String u = unico("analista");
        long id = crearUsuarioPorApi(datosUsuario(u, Perfil.ANALISTA.id()));
        assertThat(jdbc.sql("SELECT perfil_id FROM usuarios_perfiles WHERE usuario_id = :id").param("id", id).query(Integer.class).list())
                .containsExactly(10);
    }
}
