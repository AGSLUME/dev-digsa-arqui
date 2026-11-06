package co.com.agsamericas.digsa.comun.archivos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archivos en una carpeta (en local y en Docker, el volumen del servicio; ADR-005), fuera de cualquier carpeta web. Como
 * {@code plataforma.AlmacenLocal} del monolito: rutas relativas con nombre aleatorio, nunca sobrescribe y nunca sale de la raíz. Además
 * (revisión de seguridad de la integradora, BAJA 6): la carpeta se comprueba por su ruta real (un enlace simbólico no saca el archivo de la
 * raíz), la ruta tiene un tope de largo, y {@link #guardar} escribe en un temporal y lo publica de forma atómica con permisos 0600.
 */
public class AlmacenDisco implements Almacen {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenDisco.class);
    /** Ruta relativa segura: sin «..», sin rutas absolutas y con extensión (spec archivos). */
    static final Pattern RUTA = Pattern.compile("^[A-Za-z0-9_/-]+\\.[a-z0-9]+$");
    static final int MAX_RUTA = 200;

    private final Path raiz;

    public AlmacenDisco(Path raiz) {
        this.raiz = raiz.toAbsolutePath().normalize();
    }

    /** La ruta dentro de la raíz, por texto (antes de tocar el disco). */
    private Path resolver(String ruta) {
        if (ruta == null || ruta.length() > MAX_RUTA || !RUTA.matcher(ruta).matches() || ruta.contains("..") || ruta.startsWith("/")) {
            throw new IllegalArgumentException("Ruta de archivo no válida");
        }
        Path p = raiz.resolve(ruta).normalize();
        if (!p.startsWith(raiz)) {
            throw new IllegalArgumentException("Ruta de archivo fuera del almacén");
        }
        return p;
    }

    /** La ruta, comprobando que su carpeta real (siguiendo enlaces simbólicos) sigue dentro de la raíz real. */
    private Path real(Path p) throws IOException {
        Path padre = p.getParent();
        if (Files.exists(padre) && !padre.toRealPath().startsWith(raiz.toRealPath())) {
            throw new IllegalArgumentException("Ruta de archivo fuera del almacén");
        }
        return p;
    }

    private Path carpeta(Path p) throws IOException {
        Files.createDirectories(raiz);
        Files.createDirectories(p.getParent());
        return real(p);
    }

    @Override
    public void guardar(String ruta, byte[] contenido) throws IOException {
        Path p = carpeta(resolver(ruta));
        Path temporal = p.resolveSibling(".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporal, contenido, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            soloDueno(temporal);
            // Publicación atómica que NUNCA sobrescribe: el enlace duro falla si el destino ya existe (un rename sí lo pisaría)
            try {
                Files.createLink(p, temporal);
            } catch (UnsupportedOperationException e) {
                Files.move(temporal, p); // sin REPLACE_EXISTING: falla si existe
            }
        } catch (FileAlreadyExistsException e) {
            throw e; // nunca se toca un archivo que ya existía
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    private static void soloDueno(Path p) throws IOException {
        if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Override
    public InputStream leer(String ruta) throws IOException {
        return Files.newInputStream(real(resolver(ruta)));
    }

    @Override
    public OutputStream escribir(String ruta) throws IOException {
        Path p = carpeta(resolver(ruta));
        OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        soloDueno(p);
        return out;
    }

    @Override
    public void descartar(String ruta) {
        try {
            Files.deleteIfExists(real(resolver(ruta)));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("[almacen] No se pudo descartar un archivo: {}", e.getMessage());
        }
    }

    @Override
    public void verificar(String ruta, String sha256Esperado) throws IOException {
        real(resolver(ruta)); // ruta inválida → IllegalArgumentException antes de abrir nada
        Almacen.super.verificar(ruta, sha256Esperado);
    }
}
