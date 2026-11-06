package co.com.agsamericas.digsa.comun.archivos;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Almacén y validador de archivos para todo servicio que dependa de {@code lume-comun} (decisión D3). La carpeta es la del volumen del
 * servicio ({@code lume.archivos.carpeta}), sin valor por omisión (seguridad, BAJA 6): un servicio que usa el almacén y no la declara no
 * arranca; los que no guardan archivos (seguridad, hola) no la necesitan. Un servicio puede declarar su propio {@link Almacen} (p. ej. S3 en el cierre).
 */
@AutoConfiguration
public class ConfiguracionArchivos {

    /** Solo si el servicio declara su carpeta: quien necesita un {@link Almacen} sin declararla no arranca (falta el bean). */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("lume.archivos.carpeta")
    Almacen almacen(@Value("${lume.archivos.carpeta}") String carpeta) {
        if (carpeta == null || carpeta.isBlank()) {
            // Vacía sería el directorio de trabajo: nunca (revisión Java de VA2.S, BAJO 7)
            throw new IllegalStateException("lume.archivos.carpeta está vacía: declare la carpeta del volumen del servicio.");
        }
        return new AlmacenDisco(Path.of(carpeta));
    }

    @Bean
    @ConditionalOnMissingBean
    ValidadorArchivos validadorArchivos() {
        return new ValidadorArchivos();
    }
}
