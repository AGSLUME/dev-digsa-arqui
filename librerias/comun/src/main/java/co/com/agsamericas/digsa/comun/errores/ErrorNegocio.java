package co.com.agsamericas.digsa.comun.errores;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Error que el usuario puede corregir o entender: se responde con su estado HTTP y el mensaje tal cual. Misma API que
 * {@code plataforma.ErrorNegocio} del monolito, para portar el código sin cambios; la respuesta la arma {@link ManejadorErrores}.
 */
public class ErrorNegocio extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus estado;
    private final transient Map<String, Object> datos;

    public ErrorNegocio(HttpStatus estado, String mensaje) {
        this(estado, mensaje, Map.of());
    }

    public ErrorNegocio(HttpStatus estado, String mensaje, Map<String, Object> datos) {
        super(mensaje);
        this.estado = estado;
        this.datos = Map.copyOf(datos);
    }

    public static ErrorNegocio validacion(String mensaje) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, mensaje);
    }

    /** 422 de un campo: además del mensaje lleva {@code campo} para que la interfaz lo marque (Anexo 5 num. 2.4). */
    public static ErrorNegocio validacion(String mensaje, String campo) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, mensaje, Map.of("campo", campo));
    }

    /** El mismo error con el campo al que se refiere. */
    public ErrorNegocio conCampo(String campo) {
        Map<String, Object> d = new LinkedHashMap<>(datos);
        d.put("campo", campo);
        return new ErrorNegocio(estado, getMessage(), d);
    }

    public static ErrorNegocio noEncontrado(String mensaje) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, mensaje);
    }

    public static ErrorNegocio conflicto(String mensaje) {
        return new ErrorNegocio(HttpStatus.CONFLICT, mensaje);
    }

    public HttpStatus estado() {
        return estado;
    }

    public Map<String, Object> datos() {
        return datos;
    }
}
