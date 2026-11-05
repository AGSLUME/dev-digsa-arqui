package co.com.agsamericas.digsa.seguridad.identidad;

import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * SOLO PERFIL LOCAL: deja el usuario de desarrollo del {@code .env} ({@code LUME_USUARIO_DEV} / {@code LUME_CLAVE_DEV}, ficticio, con el
 * correo {@code <usuario>@<LUME_DOMINIO_PRUEBA>}) como Administrador con la contraseña definida, para el humo de punta a punta
 * ({@code infra/humo.sh}, que lee el código MFA de Mailpit). Reemplaza al usuario en memoria del andamiaje de I1. Nunca existe fuera de
 * {@code local}: el primer administrador real lo crea {@code CrearAdministrador} (S2).
 */
@Component
@Profile("local")
class UsuarioDesarrolloLocal implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioDesarrolloLocal.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder codificador;
    private final Clock reloj;
    private final String usuario;
    private final String clave;
    private final String dominio;

    UsuarioDesarrolloLocal(JdbcClient jdbc, PasswordEncoder codificador, Clock reloj, @Value("${LUME_USUARIO_DEV:}") String usuario,
                           @Value("${LUME_CLAVE_DEV:}") String clave, @Value("${LUME_DOMINIO_PRUEBA:}") String dominio) {
        this.jdbc = jdbc;
        this.codificador = codificador;
        this.reloj = reloj;
        this.usuario = usuario == null ? "" : usuario.strip().toLowerCase();
        this.clave = clave;
        this.dominio = dominio == null ? "" : dominio.strip().toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuario.isEmpty() || clave == null || clave.isBlank() || !dominio.endsWith(".prueba.local")) {
            LOG.info("[local] Sin usuario de desarrollo (LUME_USUARIO_DEV, LUME_CLAVE_DEV y un dominio *.prueba.local)");
            return;
        }
        var ahora = reloj.instant().atOffset(ZoneOffset.UTC);
        long id = jdbc.sql("""
                INSERT INTO usuarios (nombre, usuario, correo, clave_hash, clave_definida, clave_vence, clave_actualizada, creado, actualizado)
                VALUES (:n, :u, :c, :h, true, :v, :a, :a, :a)
                ON CONFLICT (usuario) DO UPDATE SET clave_hash = EXCLUDED.clave_hash, clave_definida = true, clave_vence = EXCLUDED.clave_vence,
                  activo = true, debe_cambiar_clave = false, clave_inicial = false, intentos = 0, bloqueado_hasta = NULL, actualizado = EXCLUDED.actualizado
                RETURNING id""")
                .param("n", "Desarrollo Local (ficticio)").param("u", usuario).param("c", usuario + "@" + dominio)
                .param("h", codificador.encode(clave)).param("v", ahora.plus(Duration.ofDays(30))).param("a", ahora)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p) ON CONFLICT DO NOTHING")
                .param("u", id).param("p", Perfil.ADMINISTRADOR.id()).update();
        LOG.info("[local] Usuario de desarrollo listo: {} ({}@{})", usuario, usuario, dominio);
    }
}
