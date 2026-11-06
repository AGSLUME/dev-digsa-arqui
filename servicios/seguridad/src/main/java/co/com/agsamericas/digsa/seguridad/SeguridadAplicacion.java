package co.com.agsamericas.digsa.seguridad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class SeguridadAplicacion {

    public static void main(String[] args) {
        SpringApplication.run(SeguridadAplicacion.class, args);
    }
}
