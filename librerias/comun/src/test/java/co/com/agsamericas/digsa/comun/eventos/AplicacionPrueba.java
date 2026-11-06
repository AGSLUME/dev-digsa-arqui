package co.com.agsamericas.digsa.comun.eventos;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/** Contexto mínimo de las pruebas de eventos: solo la autoconfiguración (DataSource, JDBC, Rabbit y {@code ConfiguracionEventos}). */
@SpringBootConfiguration
@EnableAutoConfiguration
class AplicacionPrueba {
}
