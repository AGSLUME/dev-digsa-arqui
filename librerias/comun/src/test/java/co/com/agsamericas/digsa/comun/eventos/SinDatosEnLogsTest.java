package co.com.agsamericas.digsa.comun.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Prueba 10 del diseño S2-OUTBOX (Ley 1581): ningún log contiene los datos del evento (aquí un documento ficticio) al publicar, despachar
 * ni fallar. Se captura con un appender de Logback en la raíz y el paquete {@code co.com.agsamericas} en TRACE, para que también salga
 * lo que el código de LUME registre en DEBUG o TRACE.
 */
class SinDatosEnLogsTest extends PruebaEventosBase {

    private static final String DOCUMENTO = "99887766";
    private static final String NOMBRE = "Persona Ficticia Logs";
    private static final Map<String, Object> DATOS = Map.of("documento", DOCUMENTO, "nombre", NOMBRE);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level nivelLume;

    @BeforeEach
    void capturar() {
        Logger raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger lume = (Logger) LoggerFactory.getLogger("co.com.agsamericas");
        nivelLume = lume.getLevel();
        lume.setLevel(Level.TRACE);
        appender.start();
        raiz.addAppender(appender);
    }

    @AfterEach
    void restaurar() {
        Logger raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        raiz.detachAppender(appender);
        appender.stop();
        ((Logger) LoggerFactory.getLogger("co.com.agsamericas")).setLevel(nivelLume);
    }

    private List<String> lineas() {
        return appender.list.stream().map(e -> e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? ""
                : ThrowableProxyUtil.asString(e.getThrowableProxy())) + " " + java.util.Arrays.toString(e.getArgumentArray())).toList();
    }

    @Test
    @DisplayName("10: publicar, despachar bien, despachar con el broker caído y un tipo inválido no escriben los datos en ningún log")
    void ningunLogLlevaLosDatos() {
        colaEnlazada("#");

        // Publicar (y un tipo inválido que lanza excepción)
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, DATOS);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> publicador.publicar("Usuario Creado", "usuario:7", 1, DATOS)))
                .isInstanceOf(IllegalArgumentException.class);

        // Despachar con el broker caído (falla y queda para reintento)
        InfraEventos.PROXY.caer();
        assertThat(despachador.despachar()).isZero();
        String errorGuardado = jdbc.sql("SELECT error FROM outbox WHERE id = :id").param("id", id).query(String.class).single();
        assertThat(errorGuardado).as("tampoco el error guardado en la fila").doesNotContain(DOCUMENTO).doesNotContain(NOMBRE);

        // Despachar bien tras reponer
        InfraEventos.PROXY.reponer();
        jdbc.sql("UPDATE outbox SET siguiente = now() - interval '1 second'").update();
        assertThat(despachador.despachar()).isEqualTo(1);

        assertThat(lineas()).isNotEmpty();
        assertThat(lineas()).noneMatch(l -> l.contains(DOCUMENTO) || l.contains(NOMBRE));
    }

    @Test
    @DisplayName("10: el WARN de un envío fallido lleva el id, el tipo y los intentos, pero no los datos")
    void warnConIdTipoEIntentos() {
        UUID id = publicarEnTransaccion("usuario.creado", "usuario:7", 1, DATOS);
        InfraEventos.PROXY.caer();
        despachador.despachar();

        List<ILoggingEvent> avisos = appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)
                && e.getLoggerName().startsWith("co.com.agsamericas")).toList();
        assertThat(avisos).as("el fallo se registra como WARN").isNotEmpty();
        String texto = avisos.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + " " + b);
        assertThat(texto).contains(id.toString()).contains("usuario.creado").doesNotContain(DOCUMENTO).doesNotContain(NOMBRE);
    }
}
