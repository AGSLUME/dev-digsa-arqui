package co.com.agsamericas.digsa.comun.errores;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Respuesta de error única (RFC 7807, ver {@link Problemas}) con los mismos estados y textos que {@code plataforma.ManejadorErrores} del
 * monolito. Nunca expone el detalle interno.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    static final String PETICION_MAL = "La petición no tiene el formato esperado.";
    static final String SIN_PERMISO = "No tiene permiso para esta acción.";
    static final String MUY_GRANDE = "Los archivos superan el tamaño máximo permitido por petición.";
    static final String METODO = "Método no permitido.";
    static final String NO_EXISTE = "No existe.";
    static final String SIN_SESION = "La sesión expiró. Vuelva a iniciar sesión.";
    static final String INTERNO = "Ocurrió un error en el servidor. Intente de nuevo o avise al administrador.";

    @ExceptionHandler(ErrorNegocio.class)
    ResponseEntity<ProblemDetail> negocio(ErrorNegocio e) {
        return responder(Problemas.de(e.estado(), e.getMessage(), e.datos()));
    }

    /** 422 con el primer error de campo; lleva {@code campo} para que la interfaz lo marque (Anexo 5 num. 2.4). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validacion(MethodArgumentNotValidException e) {
        var primero = e.getBindingResult().getFieldErrors().stream().findFirst();
        String mensaje = primero
                .map(f -> f.getDefaultMessage() == null ? "Hay un dato no válido: " + f.getField() + "." : f.getDefaultMessage())
                .orElse("Hay datos no válidos.");
        Map<String, Object> datos = primero.<Map<String, Object>>map(f -> Map.of("campo", f.getField())).orElse(Map.of());
        return responder(Problemas.de(HttpStatus.UNPROCESSABLE_ENTITY, mensaje, datos));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> peticionMal(Exception e) {
        return responder(Problemas.de(HttpStatus.BAD_REQUEST, PETICION_MAL));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denegado(AccessDeniedException e) {
        return responder(Problemas.de(HttpStatus.FORBIDDEN, SIN_PERMISO));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> muyGrande(MaxUploadSizeExceededException e) {
        return responder(Problemas.de(HttpStatus.PAYLOAD_TOO_LARGE, MUY_GRANDE));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> metodo(HttpRequestMethodNotSupportedException e) {
        return responder(Problemas.de(HttpStatus.METHOD_NOT_ALLOWED, METODO));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noExiste(NoResourceFoundException e) {
        return responder(Problemas.de(HttpStatus.NOT_FOUND, NO_EXISTE));
    }

    /** 401 desde la seguridad por métodos: mismo cuerpo que el resto de la API. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> sinSesion(AuthenticationException e) {
        return responder(Problemas.de(HttpStatus.UNAUTHORIZED, SIN_SESION, Map.of("motivo", "sin_sesion")));
    }

    /**
     * Lo no controlado es 500 con registro de incidente, salvo las excepciones de Spring que ya traen un estado 4xx (415, cabecera
     * faltante, {@code ResponseStatusException}...): se respeta su estado, sin registrarlas como incidente y con texto genérico.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> interno(Exception e) {
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode estado = er.getStatusCode();
            return responder(Problemas.de(estado, estado.value() == 404 ? NO_EXISTE : PETICION_MAL));
        }
        LOG.error("Error no controlado", e);
        return responder(Problemas.de(HttpStatus.INTERNAL_SERVER_ERROR, INTERNO));
    }

    private static ResponseEntity<ProblemDetail> responder(ProblemDetail p) {
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
