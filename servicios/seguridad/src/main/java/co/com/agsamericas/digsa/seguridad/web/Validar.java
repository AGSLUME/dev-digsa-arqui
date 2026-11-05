package co.com.agsamericas.digsa.seguridad.web;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;

/**
 * Validación de entrada con los textos del monolito ({@code @NotBlank(message = ...)} y {@code @Size(max = ...)} de sus controladores).
 * Se hace aquí y no con las anotaciones generadas del contrato, cuyos mensajes son los genéricos. 422 con {@code campo}.
 */
final class Validar {

    private Validar() {
    }

    /** Obligatorio (no vacío ni solo espacios) y con un largo máximo. */
    static String obligatorio(String valor, String campo, String mensaje, int maximo) {
        if (valor == null || valor.isBlank()) {
            throw ErrorNegocio.validacion(mensaje, campo);
        }
        return maximo(valor, campo, maximo);
    }

    /** Opcional, con un largo máximo. */
    static String maximo(String valor, String campo, int maximo) {
        if (valor != null && valor.length() > maximo) {
            throw ErrorNegocio.validacion("el tamaño debe estar entre 0 y " + maximo, campo);
        }
        return valor;
    }
}
