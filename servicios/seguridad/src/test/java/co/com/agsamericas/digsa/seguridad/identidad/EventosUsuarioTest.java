package co.com.agsamericas.digsa.seguridad.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.com.agsamericas.digsa.comun.eventos.DespachadorOutbox;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.seguridad.soporte.DatosPrueba;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * S2-OUTBOX, parte B: los eventos de usuario, cuenta y sesión que {@code seguridad} publica al outbox en la misma transacción del cambio
 * (diseño S2-OUTBOX). Una cola temporal se enlaza a {@code seguridad.eventos} con {@code #}; la prueba llama a
 * {@code DespachadorOutbox.despachar()} (el ciclo automático está apagado con {@code lume.eventos.cada-ms: 0}) y lee la cola.
 * Cada prueba se limita a los eventos de su propio usuario ({@code datos.uid}). Solo datos ficticios.
 */
class EventosUsuarioTest extends AdministracionApiBase {

    private static final String EXCHANGE = "seguridad.eventos";

    @Autowired
    DespachadorOutbox despachador;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @Autowired
    org.testcontainers.rabbitmq.RabbitMQContainer contenedorRabbit;
    /** El usuario seguridad no puede enlazar «#» en su propio exchange (matriz de ADR-006): la lectura va con el administrador. */
    private org.springframework.amqp.rabbit.connection.CachingConnectionFactory fabricaAdmin;
    private RabbitTemplate rabbit;
    private RabbitAdmin adminLector;
    @Autowired
    EventosUsuario eventosUsuario;
    @Autowired
    TransactionTemplate tx;

    private String cola;

    /** Un evento leído de la cola: el mensaje, el sobre parseado y la fila del outbox (por el messageId). */
    private record Evento(Message mensaje, JsonNode sobre) {
        String tipo() {
            return sobre.path("tipo").asString();
        }

        long version() {
            return sobre.path("version").asLong();
        }

        JsonNode datos() {
            return sobre.path("datos");
        }

        String id() {
            return mensaje.getMessageProperties().getMessageId();
        }
    }

    @BeforeEach
    void colaTemporal() {
        // Lo que dejaron otras pruebas del contexto compartido no debe estorbar
        jdbc.sql("DELETE FROM outbox").update();
        buffer.clear();
        cola = "seguridad.prueba." + UUID.randomUUID(); // el usuario seguridad solo configura seguridad.*
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        if (fabricaAdmin == null) {
            fabricaAdmin = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(contenedorRabbit.getHost(), contenedorRabbit.getAmqpPort());
            fabricaAdmin.setUsername(co.com.agsamericas.digsa.seguridad.soporte.ContenedoresPrueba.ADMIN_USUARIO);
            fabricaAdmin.setPassword(co.com.agsamericas.digsa.seguridad.soporte.ContenedoresPrueba.ADMIN_CLAVE);
            rabbit = new RabbitTemplate(fabricaAdmin);
            adminLector = new RabbitAdmin(fabricaAdmin);
        }
        rabbitAdmin.initialize(); // declara el exchange propio con sus argumentos (alternate-exchange), como al conectar
        Queue q = new Queue(cola, false, true, false); // exclusiva, sin autoborrado (receive() cancela su consumidor); se borra al final
        adminLector.declareQueue(q);
        adminLector.declareBinding(BindingBuilder.bind(q).to(exchange).with("#"));
    }

    @AfterEach
    void borrarCola() {
        adminLector.deleteQueue(cola);
    }

    // ---------- Ayudas ----------

    private final List<Evento> buffer = new ArrayList<>();

    /** Despacha todo lo pendiente y devuelve los eventos del usuario (los de otros usuarios quedan en un buffer). */
    private List<Evento> eventosDe(long uid) {
        int vueltas = 0;
        while (despachador.despachar() > 0 && ++vueltas < 100) {
            // hasta vaciar el outbox
        }
        Message m;
        while ((m = rabbit.receive(cola, 700)) != null) {
            buffer.add(new Evento(m, JSON.readTree(new String(m.getBody(), StandardCharsets.UTF_8))));
        }
        List<Evento> r = new ArrayList<>(buffer.stream().filter(e -> e.datos().path("uid").asLong(-1) == uid).toList());
        buffer.removeAll(r); // los de otros usuarios quedan para su propia lectura
        return r;
    }

    private String agregadoDe(Evento e) {
        return jdbc.sql("SELECT agregado FROM outbox WHERE id = :id").param("id", UUID.fromString(e.id())).query(String.class).single();
    }

    private static RequestPostProcessor gateway() {
        return jwt().jwt(j -> j.subject("gateway").claim("scope", "seguridad:sesiones"))
                .authorities(new SimpleGrantedAuthority("SCOPE_seguridad:sesiones"));
    }

    private static List<String> textos(JsonNode arreglo) {
        List<String> r = new ArrayList<>();
        arreglo.forEach(x -> r.add(x.asString()));
        return r;
    }

    private int crearRol(String nombre, int base, List<String> pantallas) throws Exception {
        Map<String, Object> cuerpo = rol(nombre, base, pantallas);
        return json(publicar("/api/usuarios/roles", cuerpo).andExpect(status().isCreated())).path("id").asInt();
    }

    private static Map<String, Object> rol(String nombre, int base, List<String> pantallas) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", nombre);
        m.put("descripcion", "Rol de prueba");
        m.put("baseId", base);
        m.put("pantallas", pantallas);
        return m;
    }

    /** Usuario cuyo único perfil es el rol adicional (la asignación va directo a la base de prueba, como en RolesApiTest). */
    private DatosPrueba.Ficticio usuarioConRol(int rolId) {
        var u = datos.usuario(Perfil.COORDINADOR);
        jdbc.sql("DELETE FROM usuarios_perfiles WHERE usuario_id = :u").param("u", u.id()).update();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", u.id()).param("p", rolId).update();
        return u;
    }

    // ---------- 1. Crear ----------

    @Test
    @DisplayName("B1: crear un usuario publica usuario.creado con la foto completa, sin teléfono, claveHash ni intentos; versión 1 y agregado usuario:<id>")
    void crearPublicaUsuarioCreado() throws Exception {
        String u = unico("evcrea");
        Map<String, Object> cuerpo = datosUsuario(u, Perfil.AUDITOR.id());
        cuerpo.put("telefono", "3001234567");
        long id = crearUsuarioPorApi(cuerpo);

        List<Evento> eventos = eventosDe(id);

        assertThat(eventos).hasSize(1);
        Evento e = eventos.getFirst();
        assertThat(e.tipo()).isEqualTo("usuario.creado");
        assertThat(e.version()).isEqualTo(1);
        assertThat(e.sobre().path("origen").asString()).isEqualTo("seguridad");
        assertThat(e.sobre().path("id").asString()).isEqualTo(e.id());
        assertThat(e.mensaje().getMessageProperties().getReceivedRoutingKey()).isEqualTo("usuario.creado");
        assertThat(e.mensaje().getMessageProperties().getAppId()).isEqualTo("seguridad");
        assertThat(agregadoDe(e)).isEqualTo("usuario:" + id);

        JsonNode d = e.datos();
        assertThat(d.propertyNames()).containsExactlyInAnyOrder("uid", "usuario", "nombre", "documento", "correo", "activo", "bloqueado",
                "bloqueadoHasta", "perfiles", "roles", "pantallas", "alcance", "asignados");
        assertThat(d.path("uid").asLong()).isEqualTo(id);
        assertThat(d.path("usuario").asString()).isEqualTo(u);
        assertThat(d.path("nombre").asString()).isEqualTo("Persona Ficticia " + u);
        assertThat(d.path("documento").asString()).isEqualTo("12345678");
        assertThat(d.path("correo").asString()).isEqualTo(correoDe(u));
        assertThat(d.path("activo").asBoolean()).isTrue();
        assertThat(d.path("bloqueado").asBoolean()).isFalse();
        assertThat(d.get("bloqueadoHasta").isNull()).isTrue();
        assertThat(textos(d.path("perfiles"))).containsExactly("AUDITOR");
        assertThat(d.path("roles").isArray()).isTrue();
        assertThat(d.path("roles")).isEmpty();
        assertThat(textos(d.path("pantallas"))).contains("MIS_VISITAS").doesNotContain("USUARIOS");
        assertThat(d.path("alcance").propertyNames()).containsExactlyInAnyOrder("tipo", "fuerza", "punto", "regional");
        assertThat(d.path("asignados").isArray()).isTrue();

        // Lo que NUNCA viaja: ni como clave ni como texto en ninguna parte del sobre
        String texto = e.sobre().toString();
        assertThat(d.has("telefono")).isFalse();
        assertThat(d.has("claveHash")).isFalse();
        assertThat(d.has("intentos")).isFalse();
        assertThat(texto).doesNotContain("3001234567").doesNotContain("clave_hash").doesNotContain("claveHash").doesNotContain("$2a$")
                .doesNotContain("token").doesNotContain("sesion").doesNotContain("intentos");
    }

    @Test
    @DisplayName("B1: las pantallas de la foto siguen el orden del enum Pantallas")
    void pantallasEnElOrdenDelEnum() throws Exception {
        long id = crearUsuarioPorApi(datosUsuario(unico("evorden"), Perfil.DIGSA.id()));
        List<String> pantallas = textos(eventosDe(id).getFirst().datos().path("pantallas"));
        assertThat(pantallas).hasSizeGreaterThan(1);
        List<Integer> ordinales = pantallas.stream().map(p -> Pantallas.valueOf(p).ordinal()).toList();
        assertThat(ordinales).isSorted();
    }

    // ---------- 2. Editar ----------

    @Test
    @DisplayName("B2: editar el nombre publica usuario.actualizado (versión 2); editar los perfiles publica usuario.permisos_cambiados con las pantallas nuevas")
    void editarPublicaActualizadoYPermisosCambiados() throws Exception {
        String u = unico("evedita");
        long id = crearUsuarioPorApi(datosUsuario(u, Perfil.AUDITOR.id()));
        assertThat(eventosDe(id)).extracting(Evento::tipo).containsExactly("usuario.creado");

        Map<String, Object> soloNombre = datosUsuario(u, Perfil.AUDITOR.id());
        soloNombre.put("nombre", "Otra Persona Ficticia");
        poner("/api/usuarios/" + id, soloNombre).andExpect(status().isOk());
        List<Evento> primero = eventosDe(id);
        assertThat(primero).hasSize(1);
        assertThat(primero.getFirst().tipo()).isEqualTo("usuario.actualizado");
        assertThat(primero.getFirst().version()).isEqualTo(2);
        assertThat(primero.getFirst().datos().path("nombre").asString()).isEqualTo("Otra Persona Ficticia");
        assertThat(agregadoDe(primero.getFirst())).isEqualTo("usuario:" + id);

        Map<String, Object> otroPerfil = datosUsuario(u, Perfil.DIGSA.id());
        otroPerfil.put("nombre", "Otra Persona Ficticia");
        poner("/api/usuarios/" + id, otroPerfil).andExpect(status().isOk());
        List<Evento> segundo = eventosDe(id);
        assertThat(segundo).hasSize(1);
        Evento e = segundo.getFirst();
        assertThat(e.tipo()).isEqualTo("usuario.permisos_cambiados");
        assertThat(e.version()).isEqualTo(3);
        assertThat(textos(e.datos().path("perfiles"))).containsExactly("DIGSA");
        assertThat(textos(e.datos().path("pantallas"))).contains("CALENDARIO").doesNotContain("MIS_VISITAS");
    }

    // ---------- 3. Inactivar ----------

    @Test
    @DisplayName("B3: inactivar publica usuario.estado_cambiado con activo=false y, si tenía sesión, sesion.cerrada")
    void inactivarPublicaEstadoYCierraSesion() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        UUID sid = datos.abrirSesion(u.id());

        poner("/api/usuarios/" + u.id() + "/estado", Map.of("activo", false)).andExpect(status().isOk());

        List<Evento> eventos = eventosDe(u.id());
        assertThat(eventos).extracting(Evento::tipo).containsExactlyInAnyOrder("usuario.estado_cambiado", "sesion.cerrada");
        Evento estado = eventos.stream().filter(e -> e.tipo().equals("usuario.estado_cambiado")).findFirst().orElseThrow();
        assertThat(estado.datos().path("activo").asBoolean()).isFalse();
        assertThat(estado.version()).isEqualTo(1);
        assertThat(agregadoDe(estado)).isEqualTo("usuario:" + u.id());

        Evento cierre = eventos.stream().filter(e -> e.tipo().equals("sesion.cerrada")).findFirst().orElseThrow();
        assertThat(cierre.datos().path("sid").asString()).isEqualTo(sid.toString());
        assertThat(cierre.datos().path("uid").asLong()).isEqualTo(u.id());
        assertThat(cierre.datos().path("motivo").asString()).isNotBlank();
        assertThat(cierre.version()).isEqualTo(1);
        assertThat(agregadoDe(cierre)).isEqualTo("sesion:" + sid);
    }

    @Test
    @DisplayName("B3: inactivar sin sesión abierta no publica sesion.cerrada; reactivar publica estado_cambiado con activo=true")
    void inactivarSinSesionYReactivar() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        poner("/api/usuarios/" + u.id() + "/estado", Map.of("activo", false)).andExpect(status().isOk());
        assertThat(eventosDe(u.id())).extracting(Evento::tipo).containsExactly("usuario.estado_cambiado");

        poner("/api/usuarios/" + u.id() + "/estado", Map.of("activo", true)).andExpect(status().isOk());
        List<Evento> eventos = eventosDe(u.id());
        assertThat(eventos).extracting(Evento::tipo).containsExactly("usuario.estado_cambiado");
        assertThat(eventos.getFirst().datos().path("activo").asBoolean()).isTrue();
        assertThat(eventos.getFirst().version()).isEqualTo(2);
    }

    // ---------- 4. Bloqueo y desbloqueo ----------

    @Test
    @DisplayName("B4: cinco intentos fallidos publican cuenta.bloqueada con bloqueado=true; desbloquear publica usuario.actualizado con bloqueado=false")
    void bloqueoYDesbloqueo() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        var n = navegador();
        for (int i = 0; i < 4; i++) {
            n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", "Errada.Clave." + i)).andExpect(status().isUnauthorized());
        }
        assertThat(eventosDe(u.id())).as("con 4 intentos aún no hay bloqueo").isEmpty();

        n.post("/api/auth/ingreso", Map.of("usuario", u.usuario(), "clave", "Errada.Clave.4")).andExpect(status().isUnauthorized());
        List<Evento> bloqueo = eventosDe(u.id());
        assertThat(bloqueo).hasSize(1);
        Evento b = bloqueo.getFirst();
        assertThat(b.tipo()).isEqualTo("cuenta.bloqueada");
        assertThat(b.version()).isEqualTo(1);
        assertThat(b.datos().path("bloqueado").asBoolean()).isTrue();
        assertThat(b.datos().path("bloqueadoHasta").asString()).as("ISO-8601").isNotBlank();
        assertThat(b.datos().path("uid").asLong()).isEqualTo(u.id());
        assertThat(b.datos().has("intentos")).isFalse();
        assertThat(agregadoDe(b)).isEqualTo("usuario:" + u.id());

        publicar("/api/usuarios/" + u.id() + "/desbloquear", null).andExpect(status().is2xxSuccessful());
        List<Evento> desbloqueo = eventosDe(u.id());
        assertThat(desbloqueo).hasSize(1);
        assertThat(desbloqueo.getFirst().tipo()).isEqualTo("usuario.actualizado");
        assertThat(desbloqueo.getFirst().version()).isEqualTo(2);
        assertThat(desbloqueo.getFirst().datos().path("bloqueado").asBoolean()).isFalse();
        assertThat(desbloqueo.getFirst().datos().get("bloqueadoHasta").isNull()).isTrue();
    }

    // ---------- 5. Cambios de un rol ----------

    @Test
    @DisplayName("B5: editar las pantallas de un rol asignado a 2 usuarios publica 2 usuario.permisos_cambiados con las pantallas nuevas; inactivarlo, otros 2")
    void editarRolNotificaASusUsuarios() throws Exception {
        String nombre = unico("Rol evento ");
        int rolId = crearRol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        var a = usuarioConRol(rolId);
        var b = usuarioConRol(rolId);
        var ajeno = datos.usuario(Perfil.COORDINADOR);

        poner("/api/usuarios/roles/" + rolId, rol(nombre, Perfil.DIGSA.id(), List.of("CALENDARIO", "REPORTES"))).andExpect(status().isOk());

        for (var u : List.of(a, b)) {
            List<Evento> eventos = eventosDe(u.id());
            assertThat(eventos).as("usuario " + u.id()).hasSize(1);
            Evento e = eventos.getFirst();
            assertThat(e.tipo()).isEqualTo("usuario.permisos_cambiados");
            assertThat(e.version()).isEqualTo(1);
            assertThat(textos(e.datos().path("pantallas"))).contains("CALENDARIO", "REPORTES");
            JsonNode roles = e.datos().path("roles");
            assertThat(roles).hasSize(1);
            assertThat(roles.get(0).path("id").asInt()).isEqualTo(rolId);
            assertThat(roles.get(0).path("base").asString()).isEqualTo("DIGSA");
            assertThat(textos(roles.get(0).path("pantallas"))).containsExactlyInAnyOrder("CALENDARIO", "REPORTES");
            assertThat(agregadoDe(e)).isEqualTo("usuario:" + u.id());
        }
        assertThat(eventosDe(ajeno.id())).as("quien no tiene el rol no recibe nada").isEmpty();

        poner("/api/usuarios/roles/" + rolId + "/estado", Map.of("activo", false)).andExpect(status().isOk());
        for (var u : List.of(a, b)) {
            List<Evento> eventos = eventosDe(u.id());
            assertThat(eventos).as("inactivar el rol, usuario " + u.id()).hasSize(1);
            assertThat(eventos.getFirst().tipo()).isEqualTo("usuario.permisos_cambiados");
            assertThat(eventos.getFirst().version()).isEqualTo(2);
        }
    }

    // ---------- 6. Rollback ----------

    @Test
    @DisplayName("B6: si la operación falla (validación o rollback) no queda ningún evento en el outbox ni se sube la versión")
    void sinEventoSiHayRollback() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        long antes = contar("SELECT count(*) FROM outbox");

        // Falla de validación por API: la fuerza de Sanidad es obligatoria
        Map<String, Object> sinFuerza = datosUsuario(u.usuario(), Perfil.SANIDAD.id());
        publicar("/api/usuarios", sinFuerza).andExpect(status().is4xxClientError());
        poner("/api/usuarios/" + u.id(), sinFuerza).andExpect(status().is4xxClientError());
        // Rol con una pantalla que no existe
        poner("/api/usuarios/roles/99999", rol("no existe", Perfil.DIGSA.id(), List.of("NO_EXISTE"))).andExpect(status().is4xxClientError());
        assertThat(contar("SELECT count(*) FROM outbox")).isEqualTo(antes);

        // Rollback explícito después de publicar: el evento y el aumento de versión se deshacen juntos
        tx.executeWithoutResult(s -> {
            eventosUsuario.publicar("usuario.actualizado", u.id());
            s.setRollbackOnly();
        });
        assertThat(contar("SELECT count(*) FROM outbox")).isEqualTo(antes);
        assertThat(contar("SELECT version FROM usuarios WHERE id = ?", u.id())).as("la versión no sube con el rollback").isZero();
        assertThat(eventosDe(u.id())).isEmpty();
    }

    // ---------- 7. Versiones y concurrencia ----------

    @Test
    @DisplayName("B7: las versiones de un usuario son estrictamente crecientes (1..N sin repetidos), con una edición del usuario y una del rol en paralelo y sin interbloqueo")
    void versionesConEdicionesEnParalelo() throws Exception {
        String nombreRol = unico("Rol paralelo ");
        int rolId = crearRol(nombreRol, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        var u = usuarioConRol(rolId);
        int vueltas = 10;

        List<Callable<Integer>> tareas = new ArrayList<>();
        tareas.add(() -> {
            int peor = 200;
            for (int i = 0; i < vueltas; i++) {
                Map<String, Object> d = datosUsuario(u.usuario(), rolId);
                d.put("nombre", "Persona Ficticia Paralela " + i);
                int estado = poner("/api/usuarios/" + u.id(), d).andReturn().getResponse().getStatus();
                peor = Math.max(peor, estado);
            }
            return peor;
        });
        tareas.add(() -> {
            int peor = 200;
            for (int i = 0; i < vueltas; i++) {
                List<String> pantallas = i % 2 == 0 ? List.of("CALENDARIO", "REPORTES") : List.of("CALENDARIO");
                int estado = poner("/api/usuarios/roles/" + rolId, rol(nombreRol, Perfil.DIGSA.id(), pantallas)).andReturn().getResponse()
                        .getStatus();
                peor = Math.max(peor, estado);
            }
            return peor;
        });

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            for (Future<Integer> f : hilos.invokeAll(tareas, 120, TimeUnit.SECONDS)) {
                assertThat(f.isCancelled()).as("sin interbloqueo: terminó a tiempo").isFalse();
                assertThat(f.get()).as("todas las peticiones respondieron 200").isEqualTo(200);
            }
        } finally {
            hilos.shutdownNow();
        }

        List<Evento> eventos = eventosDe(u.id());
        assertThat(eventos).as("una edición del usuario y una del rol por vuelta").hasSize(2 * vueltas);
        List<Long> versiones = eventos.stream().map(Evento::version).sorted().toList();
        assertThat(versiones).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 2L * vueltas).boxed().toList());
        assertThat(contar("SELECT version FROM usuarios WHERE id = ?", u.id())).isEqualTo(2L * vueltas);
    }

    // ---------- Contraseña propia y carrera rol/usuario ----------

    @Test
    @DisplayName("B4: cinco contraseñas actuales erradas en «cambiar mi contraseña» publican cuenta.bloqueada")
    void cambiarMiClaveErradaCincoVecesBloquea() throws Exception {
        var u = datos.usuario(Perfil.AUDITOR);
        UUID sid = datos.abrirSesion(u.id());
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/cuenta/clave")
                    .with(token(u, sid)).contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("actual", "Errada.Numero." + i + "X", "clave", "Nueva.Clave.2026!", "confirmacion",
                            "Nueva.Clave.2026!")))).andExpect(status().isUnprocessableEntity());
        }
        assertThat(eventosDe(u.id())).as("con 4 intentos aún no hay bloqueo").isEmpty();
        mvc.perform(post("/api/cuenta/clave").with(token(u, sid)).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("actual", "Errada.Numero.4X", "clave", "Nueva.Clave.2026!", "confirmacion",
                        "Nueva.Clave.2026!")))).andExpect(status().isUnauthorized());

        List<Evento> eventos = eventosDe(u.id());
        Evento b = eventos.stream().filter(e -> e.tipo().equals("cuenta.bloqueada")).findFirst().orElseThrow();
        assertThat(eventos.stream().filter(e -> e.tipo().equals("cuenta.bloqueada")).count()).isEqualTo(1);
        assertThat(b.datos().path("bloqueado").asBoolean()).isTrue();
        assertThat(agregadoDe(b)).isEqualTo("usuario:" + u.id());
        assertThat(b.datos().has("intentos")).isFalse();
    }

    @Test
    @DisplayName("B7: asignar el rol R a U mientras se editan las pantallas de R: la foto del último evento de U lleva las pantallas nuevas de R")
    void asignarRolMientrasSeEditaElRol() throws Exception {
        for (int ronda = 0; ronda < 5; ronda++) {
            String nombreRol = unico("Rol carrera ");
            int rolId = crearRol(nombreRol, Perfil.DIGSA.id(), List.of("CALENDARIO"));
            var u = datos.usuario(Perfil.COORDINADOR);

            List<Callable<Integer>> tareas = List.of(
                    () -> poner("/api/usuarios/" + u.id(), datosUsuario(u.usuario(), rolId)).andReturn().getResponse().getStatus(),
                    () -> poner("/api/usuarios/roles/" + rolId, rol(nombreRol, Perfil.DIGSA.id(), List.of("CALENDARIO", "REPORTES")))
                            .andReturn().getResponse().getStatus());
            ExecutorService hilos = Executors.newFixedThreadPool(2);
            try {
                for (Future<Integer> f : hilos.invokeAll(tareas, 60, TimeUnit.SECONDS)) {
                    assertThat(f.get()).as("ronda " + ronda).isEqualTo(200);
                }
            } finally {
                hilos.shutdownNow();
            }

            List<Evento> eventos = eventosDe(u.id());
            assertThat(eventos).as("ronda " + ronda).isNotEmpty();
            Evento ultimo = eventos.stream().max(java.util.Comparator.comparingLong(Evento::version)).orElseThrow();
            JsonNode roles = ultimo.datos().path("roles");
            assertThat(roles).as("ronda " + ronda + ": la foto final tiene el rol").hasSize(1);
            assertThat(roles.get(0).path("id").asInt()).isEqualTo(rolId);
            assertThat(textos(roles.get(0).path("pantallas"))).as("ronda " + ronda).containsExactlyInAnyOrder("CALENDARIO", "REPORTES");
            assertThat(textos(ultimo.datos().path("pantallas"))).contains("REPORTES");
        }
    }

    // ---------- 8. Cierre de sesión por el gateway ----------

    @Test
    @DisplayName("B8: el cierre de sesión por el gateway publica sesion.cerrada {sid, uid, motivo: salida}; repetirlo no publica otro")
    void cierreDeSesionPorElGateway() throws Exception {
        var u = datos.usuario(Perfil.COORDINADOR);
        UUID sid = datos.abrirSesion(u.id());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/interno/sesiones/" + sid + "/cerrar").with(gateway()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"motivo\":\"salida\"}")).andExpect(status().isNoContent());
        }

        List<Evento> eventos = eventosDe(u.id());
        assertThat(eventos).hasSize(1);
        Evento e = eventos.getFirst();
        assertThat(e.tipo()).isEqualTo("sesion.cerrada");
        assertThat(e.version()).isEqualTo(1);
        assertThat(e.datos().propertyNames()).containsExactlyInAnyOrder("sid", "uid", "motivo");
        assertThat(e.datos().path("sid").asString()).isEqualTo(sid.toString());
        assertThat(e.datos().path("uid").asLong()).isEqualTo(u.id());
        assertThat(e.datos().path("motivo").asString()).isEqualTo("salida");
        assertThat(agregadoDe(e)).isEqualTo("sesion:" + sid);
    }
}
