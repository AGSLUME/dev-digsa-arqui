package co.com.agsamericas.digsa.comun.archivos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** VA1.1: autoconfiguración del almacén y del validador. */
class ConfiguracionArchivosTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfiguracionArchivos.class));

    @Test
    void registraAlmacenDiscoYValidador(@TempDir Path carpeta) {
        runner.withPropertyValues("lume.archivos.carpeta=" + carpeta.toString().replace('\\', '/')).run(ctx -> {
            assertThat(ctx).hasSingleBean(Almacen.class).hasSingleBean(ValidadorArchivos.class);
            assertThat(ctx.getBean(Almacen.class)).isInstanceOf(AlmacenDisco.class);
        });
    }

    @Test
    void tomaLaCarpetaDeLaPropiedad(@TempDir Path carpeta) {
        runner.withPropertyValues("lume.archivos.carpeta=" + carpeta.toString().replace('\\', '/')).run(ctx -> {
            ctx.getBean(Almacen.class).guardar("p/a.txt", new byte[] {7});

            assertThat(Files.readAllBytes(carpeta.resolve("p/a.txt"))).containsExactly(7);
        });
    }

    @Test
    void respetaUnAlmacenPropio() {
        runner.withUserConfiguration(AlmacenPropio.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(Almacen.class);
            assertThat(ctx.getBean(Almacen.class)).isSameAs(AlmacenPropio.ALMACEN);
            assertThat(ctx).hasSingleBean(ValidadorArchivos.class);
        });
    }

    @Test
    void respetaUnValidadorPropio() {
        ValidadorArchivos propio = new ValidadorArchivos();
        runner.withBean(ValidadorArchivos.class, () -> propio).run(ctx -> {
            assertThat(ctx).hasSingleBean(ValidadorArchivos.class);
            assertThat(ctx.getBean(ValidadorArchivos.class)).isSameAs(propio);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AlmacenPropio {
        static final Almacen ALMACEN = new Almacen() {
            @Override
            public void guardar(String ruta, byte[] contenido) {
            }

            @Override
            public InputStream leer(String ruta) {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public OutputStream escribir(String ruta) {
                return OutputStream.nullOutputStream();
            }

            @Override
            public void descartar(String ruta) {
            }
        };

        @Bean
        Almacen almacenPropio() {
            return ALMACEN;
        }
    }
}
