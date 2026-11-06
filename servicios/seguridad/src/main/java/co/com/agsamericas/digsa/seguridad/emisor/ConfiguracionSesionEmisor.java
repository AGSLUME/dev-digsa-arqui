package co.com.agsamericas.digsa.seguridad.emisor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * La sesión del emisor ({@code LUME_IDP}, tabla {@code spring_session_attributes}) se guarda en JSON y no con la serialización de Java
 * (revisión de la integradora, M3): con serialización Java, quien lograra escribir en esa tabla (inyección SQL, credencial de la
 * aplicación) podría ejecutar código al leerla. El JSON usa los módulos de Jackson de Spring Security con su lista de tipos permitidos (el
 * contexto de seguridad, la petición guardada, los factores) más los atributos propios del ingreso, que son solo {@code Long}.
 */
@Configuration
class ConfiguracionSesionEmisor {

    /** Spring Session JDBC toma este nombre de bean para convertir los atributos de la sesión. */
    @Bean("springSessionConversionService")
    GenericConversionService conversionSesion() {
        BasicPolymorphicTypeValidator.Builder tipos = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Long.class);
        JsonMapper json = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(ConfiguracionSesionEmisor.class.getClassLoader(), tipos))
                .build();
        GenericConversionService s = new GenericConversionService();
        s.addConverter(Object.class, byte[].class, json::writeValueAsBytes);
        s.addConverter(byte[].class, Object.class, b -> json.readValue(b, Object.class));
        return s;
    }
}
