package co.com.agsamericas.digsa.comun.archivos;

import co.com.agsamericas.digsa.reglas.Huellas;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Puerto de almacenamiento de archivos (evidencias, actas, escritos), como {@code plataforma.Almacen} del monolito (ADR-005). Cada servicio
 * guarda solo sus archivos. Adaptador local en disco ({@link AlmacenDisco}, un volumen por servicio); en el cierre, un almacén de objetos
 * compatible con S3 entra como otro adaptador sin cambiar a quien guarda.
 */
public interface Almacen {

    /** Guarda el contenido en la ruta relativa (que debe ser nueva). */
    void guardar(String ruta, byte[] contenido) throws IOException;

    InputStream leer(String ruta) throws IOException;

    /**
     * Abre una ruta nueva para escribirla en flujo, sin tener el contenido en memoria (exportaciones de varios GB). Quien la abre la
     * cierra; si la operación falla, la descarta.
     */
    OutputStream escribir(String ruta) throws IOException;

    /** Borra un archivo recién escrito cuando la operación que lo contenía falló (todo o nada). Nunca lanza. */
    void descartar(String ruta);

    /**
     * Comprueba que el archivo guardado conserva su huella SHA-256 (decisión D3; en el monolito, la exportación y la descarga de soportes).
     *
     * @throws ArchivoAlterado si la huella no coincide (quien llama publica {@link ArchivoIntegridadFallida})
     * @throws java.nio.file.NoSuchFileException si el archivo falta
     */
    default void verificar(String ruta, String sha256Esperado) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
        try (InputStream in = new DigestInputStream(leer(ruta), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        String calculado = HexFormat.of().formatHex(md.digest());
        if (sha256Esperado == null || !Huellas.iguales(calculado, sha256Esperado.toLowerCase(java.util.Locale.ROOT))) {
            throw new ArchivoAlterado(ruta);
        }
    }
}
