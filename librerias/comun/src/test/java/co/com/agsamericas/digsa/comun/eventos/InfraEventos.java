package co.com.agsamericas.digsa.comun.eventos;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Contenedores únicos de las pruebas de eventos (los mismos digest de {@code infra/docker-compose.yml}). Se inician una vez por JVM.
 * El esquema se crea leyendo el recurso {@code lume/eventos/outbox.sql} del classpath, como lo copia cada servicio en su migración.
 * RabbitMQ arranca como en el compose: {@code infra/rabbitmq/usuarios.sh} crea un usuario por servicio con permisos mínimos (claves
 * ficticias). La aplicación de prueba es {@code seguridad} (productor); el consumidor se simula con el usuario {@code visitas}.
 */
final class InfraEventos {

    static final DockerImageName POSTGRES = DockerImageName
            .parse("postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32")
            .asCompatibleSubstituteFor("postgres");
    static final DockerImageName RABBIT = DockerImageName
            .parse("rabbitmq:4.3.7-management@sha256:6e51f8f18e055d6dc3d94316db0073da64d811c2e19744661fae06b03c1d6fa8")
            .asCompatibleSubstituteFor("rabbitmq");

    static final List<String> SERVICIOS = List.of("seguridad", "visitas", "reportes", "notificaciones", "gateway");

    static final PostgreSQLContainer POSTGRES_CONTENEDOR = new PostgreSQLContainer(POSTGRES)
            .withDatabaseName("lume").withUsername("prueba").withPassword("prueba-solo-prueba");
    static final RabbitMQContainer RABBIT_CONTENEDOR = new RabbitMQContainer(RABBIT);
    /** Lo que ve la aplicación como «el broker»: se puede «caer» y «reponer» en las pruebas. */
    static final ProxyTcp PROXY;
    /** Conexión del usuario {@code visitas} (el consumidor simulado), directa al contenedor. */
    static final CachingConnectionFactory VISITAS;
    /** Conexión del administrador (lume_admin): la plomería de las pruebas (colas de lectura, purgas), no los permisos de un servicio. */
    static final CachingConnectionFactory ADMIN;
    static final String ADMIN_USUARIO = "lume_admin";
    static final String ADMIN_CLAVE = "admin-rabbit-solo-prueba";

    static {
        POSTGRES_CONTENEDOR.start();
        RABBIT_CONTENEDOR
                // Los mismos tres archivos que el compose: matriz de permisos (ADR-006), exchanges declarados al arrancar y tope de mensaje
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/usuarios.sh", 0755), "/opt/lume/usuarios.sh")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/10-lume.conf", 0644), "/etc/rabbitmq/conf.d/10-lume.conf")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/definiciones.json", 0644), "/etc/rabbitmq/definiciones.json")
                // Con definiciones al arrancar no existe el usuario por defecto: usuarios.sh crea al administrador con estas variables
                .withAdminUser(ADMIN_USUARIO).withAdminPassword(ADMIN_CLAVE)
                // El mismo command del compose: gosu hace que usuarios.sh corra como rabbitmq (si corre como root, .erlang.cookie queda de root y el nodo no arranca)
                .withCommand("sh", "-c", "gosu rabbitmq /opt/lume/usuarios.sh & exec docker-entrypoint.sh rabbitmq-server");
        for (String s : SERVICIOS) {
            RABBIT_CONTENEDOR.withEnv(s.toUpperCase() + "_RABBIT_CLAVE", clave(s));
        }
        RABBIT_CONTENEDOR.start();
        esperarUsuarios();
        crearEsquema();
        PROXY = new ProxyTcp(RABBIT_CONTENEDOR.getHost(), RABBIT_CONTENEDOR.getAmqpPort());
        VISITAS = fabrica("visitas");
        ADMIN = new CachingConnectionFactory(RABBIT_CONTENEDOR.getHost(), RABBIT_CONTENEDOR.getAmqpPort());
        ADMIN.setUsername(ADMIN_USUARIO);
        ADMIN.setPassword(ADMIN_CLAVE);
    }

    private InfraEventos() {
    }

    /** Clave ficticia del usuario de RabbitMQ de un servicio. */
    static String clave(String servicio) {
        return servicio + "-rabbit-solo-prueba";
    }

    static CachingConnectionFactory fabrica(String usuario) {
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT_CONTENEDOR.getHost(), RABBIT_CONTENEDOR.getAmqpPort());
        cf.setUsername(usuario);
        cf.setPassword(clave(usuario));
        return cf;
    }

    /** Conexión del cliente nativo (sin Spring) de un usuario de servicio, o del administrador si {@code usuario} es null. */
    static Connection conexionNativa(String usuario) throws Exception {
        com.rabbitmq.client.ConnectionFactory f = new com.rabbitmq.client.ConnectionFactory();
        f.setHost(RABBIT_CONTENEDOR.getHost());
        f.setPort(RABBIT_CONTENEDOR.getAmqpPort());
        f.setUsername(usuario == null ? RABBIT_CONTENEDOR.getAdminUsername() : usuario);
        f.setPassword(usuario == null ? RABBIT_CONTENEDOR.getAdminPassword() : clave(usuario));
        return f.newConnection();
    }

    /** Operación sobre un canal nativo. */
    @FunctionalInterface
    interface OperacionCanal {
        void ejecutar(Channel canal) throws Exception;
    }

    /**
     * Ejecuta la operación con un canal nuevo del usuario y devuelve el código con que el broker cerró el canal (403 = ACCESS_REFUSED,
     * 404 = NOT_FOUND), o -1 si el canal siguió abierto.
     */
    static int codigoDeCierre(String usuario, OperacionCanal operacion) throws Exception {
        try (Connection c = conexionNativa(usuario)) {
            Channel ch = c.createChannel();
            AtomicInteger codigo = new AtomicInteger(-1);
            CountDownLatch cerrado = new CountDownLatch(1);
            ch.addShutdownListener(causa -> {
                if (causa.getReason() instanceof AMQP.Channel.Close cierre) {
                    codigo.set(cierre.getReplyCode());
                }
                cerrado.countDown();
            });
            try {
                operacion.ejecutar(ch);
                // un basicPublish rechazado cierra el canal de forma asíncrona: se le da tiempo
                cerrado.await(2, TimeUnit.SECONDS);
            } catch (IOException | RuntimeException e) {
                cerrado.await(5, TimeUnit.SECONDS);
            }
            return codigo.get();
        }
    }

    /** ¿Existe el exchange? (consulta pasiva con el administrador). */
    static boolean existeExchange(String nombre) throws Exception {
        return codigoDeCierre(null, ch -> ch.exchangeDeclarePassive(nombre)) == -1;
    }

    private static void esperarUsuarios() {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (System.nanoTime() < limite) {
            try {
                ExecResult r = RABBIT_CONTENEDOR.execInContainer("test", "-f", "/var/lib/rabbitmq/.usuarios-lume-listos");
                if (r.getExitCode() == 0) {
                    return;
                }
                Thread.sleep(500);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("No se pudo comprobar que usuarios.sh terminó", e);
            }
        }
        throw new IllegalStateException("usuarios.sh no creó los usuarios de RabbitMQ a tiempo");
    }

    private static void crearEsquema() {
        try (InputStream in = InfraEventos.class.getClassLoader().getResourceAsStream("lume/eventos/outbox.sql")) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso lume/eventos/outbox.sql en el classpath");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${rol_app}", POSTGRES_CONTENEDOR.getUsername());
            try (java.sql.Connection c = DriverManager.getConnection(POSTGRES_CONTENEDOR.getJdbcUrl(), POSTGRES_CONTENEDOR.getUsername(),
                    POSTGRES_CONTENEDOR.getPassword()); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("No se pudo crear el esquema del outbox", e);
        }
    }
}
