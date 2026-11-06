package co.com.agsamericas.digsa.visitas.usuarios;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Siembra de la copia de usuarios para local y pruebas (decisión D2): hasta que lleguen los eventos {@code usuario.*} de seguridad (S2), los
 * e2e crean el usuario en seguridad y lo copian aquí. <b>Solo existe con los perfiles {@code local} o {@code prueba}</b> (fuera de ellos el
 * bean no se crea y la ruta responde 404); exige un token de máquina con el alcance {@code visitas:pruebas}. No va en el contrato público.
 * Datos siempre ficticios.
 */
@RestController
@Profile({"local", "prueba"})
@PreAuthorize("hasAuthority('SCOPE_visitas:pruebas')")
class ControladorPruebasUsuarios {

    /** La foto del usuario con los campos que usa visitas (misma forma que tendrá el evento). */
    record Usuario(Long uid, String usuario, String nombre, String documento, String correo, Boolean activo, List<Integer> perfiles,
                   String alcanceFuerza, Long alcancePunto, Long alcanceRegional) {
    }

    private final JdbcClient jdbc;

    ControladorPruebasUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/interno/pruebas/usuarios")
    @Transactional
    public ResponseEntity<Map<String, Object>> guardar(@RequestBody Usuario u) {
        if (u == null || u.uid() == null || u.usuario() == null || u.nombre() == null) {
            return ResponseEntity.badRequest().body(Map.of("mensaje", "Faltan uid, usuario o nombre."));
        }
        Short[] perfiles = u.perfiles() == null ? new Short[0] : u.perfiles().stream().filter(java.util.Objects::nonNull).map(Integer::shortValue).toArray(Short[]::new);
        jdbc.sql("""
                INSERT INTO usuarios_copia (uid, usuario, nombre, documento, correo, activo, perfiles, alcance_fuerza, alcance_punto,
                                            alcance_regional, version)
                VALUES (:uid, :usuario, :nombre, :documento, :correo, :activo, :perfiles, :fuerza, :punto, :regional, 1)
                ON CONFLICT (uid) DO UPDATE SET usuario = EXCLUDED.usuario, nombre = EXCLUDED.nombre, documento = EXCLUDED.documento,
                  correo = EXCLUDED.correo, activo = EXCLUDED.activo, perfiles = EXCLUDED.perfiles, alcance_fuerza = EXCLUDED.alcance_fuerza,
                  alcance_punto = EXCLUDED.alcance_punto, alcance_regional = EXCLUDED.alcance_regional,
                  version = usuarios_copia.version + 1, actualizado = now()""")
                .param("uid", u.uid()).param("usuario", u.usuario()).param("nombre", u.nombre()).param("documento", u.documento())
                .param("correo", u.correo()).param("activo", u.activo() == null || u.activo()).param("perfiles", perfiles)
                .param("fuerza", u.alcanceFuerza()).param("punto", u.alcancePunto()).param("regional", u.alcanceRegional()).update();
        return ResponseEntity.ok(Map.of("mensaje", "Usuario copiado."));
    }
}
