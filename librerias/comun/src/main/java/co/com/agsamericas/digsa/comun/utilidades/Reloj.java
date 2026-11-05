package co.com.agsamericas.digsa.comun.utilidades;

import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Reloj único del servicio en America/Bogota, como en el monolito. Las pruebas lo reemplazan declarando su propio {@link Clock}. */
@AutoConfiguration
public class Reloj {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(Fechas.BOGOTA);
    }
}
