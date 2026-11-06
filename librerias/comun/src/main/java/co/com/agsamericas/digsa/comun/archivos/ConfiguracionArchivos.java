package co.com.agsamericas.digsa.comun.archivos;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Almacén y validador de archivos para todo servicio que dependa de {@code lume-comun} (decisión D3). La carpeta es la del volumen del
 * servicio ({@code lume.archivos.carpeta}); un servicio puede declarar su propio {@link Almacen} (p. ej. S3 en el cierre).
 */
@AutoConfiguration
public class ConfiguracionArchivos {

    @Bean
    @ConditionalOnMissingBean
    Almacen almacen(@Value("${lume.archivos.carpeta:almacen/archivos}") String carpeta) {
        return new AlmacenDisco(Path.of(carpeta));
    }

    @Bean
    @ConditionalOnMissingBean
    ValidadorArchivos validadorArchivos() {
        return new ValidadorArchivos();
    }
}
