package co.com.agsamericas.digsa.comun;

import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoria;
import co.com.agsamericas.digsa.comun.auditoria.PublicadorAuditoriaLog;
import co.com.agsamericas.digsa.comun.errores.ManejadorErrores;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Lo que todo servicio Spring MVC de LUME recibe con solo depender de {@code lume-comun}. */
@AutoConfiguration
public class ConfiguracionComun {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    ManejadorErrores manejadorErrores() {
        return new ManejadorErrores();
    }

    @Bean
    @ConditionalOnMissingBean
    PublicadorAuditoria publicadorAuditoria() {
        return new PublicadorAuditoriaLog();
    }
}
