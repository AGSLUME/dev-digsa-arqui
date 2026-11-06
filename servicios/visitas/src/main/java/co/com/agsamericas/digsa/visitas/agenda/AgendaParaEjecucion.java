package co.com.agsamericas.digsa.visitas.agenda;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que ejecución, OPL y hallazgos (VISITAS B) piden a la agenda, con las firmas de {@code ServicioAgenda} y {@code ServicioNotificacion} del
 * monolito (develop @ 1ff82de). {@code UsuarioSesion} se reemplaza por {@link Quien}, porque estas operaciones también corren desde tareas
 * programadas, sin token. Las firmas existen desde VA1 para que B compile; la implementación (bean) llega en VA2 con la agenda.
 */
public interface AgendaParaEjecucion {

    /** Quién hace la operación: el usuario de la petición, o {@code uid = null} y {@code usuario = "sistema"} en una tarea programada. */
    record Quien(Long uid, String usuario) {
        public static Quien sistema() {
            return new Quien(null, "sistema");
        }
    }

    /** Como {@code ServicioAgenda.Resultado}. */
    record Resultado(long id, String numero, String mensaje, Boolean notificacionOk, List<String> advertencias) {
    }

    /** Como {@code ServicioNotificacion.Resultado}: nunca lanza por el correo. */
    record ResultadoNotificacion(boolean ok, String mensaje) {
    }

    /** Como {@code ServicioNotificacion.Destinatario}. */
    record Destinatario(String correo, String origen, boolean obligatorio) {
    }

    /** Como {@code ServicioNotificacion.Calculada}: destinatarios, los obligatorios que faltan, asunto y cuerpo. */
    record Calculada(List<Destinatario> destinatarios, List<String> faltan, String asunto, String cuerpo) {
    }

    /** Programa la visita de seguimiento N al plan de la visita de origen (monolito {@code ServicioAgenda.programarSeguimiento}). */
    long programarSeguimiento(RepositorioVisitas.Visita origen, LocalDate fecha, int n, String alcance, Quien quien);

    /** Notifica el seguimiento recién programado (monolito {@code ServicioAgenda.notificarSeguimiento}). */
    ResultadoNotificacion notificarSeguimiento(long id, Quien quien);

    /** Cancela el seguimiento pendiente de una visita de origen, si lo hay (monolito {@code ServicioAgenda.cancelarSeguimientoPendiente}). */
    boolean cancelarSeguimientoPendiente(long origenId, String motivo);

    /** Inicia la visita (Mis visitas; monolito {@code ServicioAgenda.iniciar}). */
    Resultado iniciar(long id, Quien quien);

    /** Destinatarios, asunto y cuerpo de la notificación de una visita (monolito {@code ServicioNotificacion.calcular}; el acta los usa). */
    Calculada calcularDestinatarios(RepositorioVisitas.Visita v, String sufijoAsunto);
}
