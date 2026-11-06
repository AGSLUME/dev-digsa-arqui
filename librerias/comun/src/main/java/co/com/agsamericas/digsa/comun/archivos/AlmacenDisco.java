package co.com.agsamericas.digsa.comun.archivos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archivos en una carpeta (en local y en Docker, el volumen del servicio; ADR-005), fuera de cualquier carpeta web. Como
 * {@code plataforma.AlmacenLocal} del monolito: rutas relativas con nombre aleatorio, nunca sobrescribe y nunca sale de la raíz. Además
 * (revisión de seguridad de la integradora, BAJA 6, y revisión Java de VA2.S):
 * <ul>
 *   <li>la carpeta se comprueba por su ruta real antes de crear nada, y el archivo final no se sigue si es un enlace simbólico;</li>
 *   <li>la ruta tiene un tope de largo;</li>
 *   <li>{@link #guardar} escribe en un temporal creado ya con permisos 0600 y lo publica sin sobrescribir: con un enlace duro (atómico) o,
 *       si el volumen no los admite, creando el destino de forma exclusiva.</li>
 * </ul>
 * Riesgo residual documentado: entre la comprobación de la carpeta y la escritura, quien tenga escritura en el volumen podría cambiarla
 * (TOCTOU); el volumen es solo del servicio.
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

    /** El ancestro existente más profundo de la ruta, por su ruta real, debe seguir dentro de la raíz real (enlaces simbólicos). */
    private Path dentro(Path p) throws IOException {
        Files.createDirectories(raiz);
        Path raizReal = raiz.toRealPath();
        Path existente = p.getParent();
        while (existente != null && !Files.exists(existente)) {
            existente = existente.getParent();
        }
        if (existente == null || !existente.toRealPath().startsWith(raizReal)) {
            throw new IllegalArgumentException("Ruta de archivo fuera del almacén");
        }
        return p;
    }

    private Path carpeta(Path p) throws IOException {
        dentro(p); // antes de crear subcarpetas: un enlace a una carpeta externa no deja crear nada fuera de la raíz
        Files.createDirectories(p.getParent());
        return dentro(p);
    }

    private static FileAttribute<?>[] soloDueno(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
    }

    @Override
    public void guardar(String ruta, byte[] contenido) throws IOException {
        Path p = carpeta(resolver(ruta));
        Path temporal = p.resolveSibling(".tmp-" + UUID.randomUUID());
        try {
            Files.createFile(temporal, soloDueno(temporal)); // con 0600 desde que existe
            Files.write(temporal, contenido, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                // Publicación atómica que NUNCA sobrescribe: el enlace duro falla si el destino ya existe (un rename sí lo pisaría)
                Files.createLink(p, temporal);
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (UnsupportedOperationException | FileSystemException e) {
                // Volumen sin enlaces duros (CIFS, FUSE…): el destino se crea de forma exclusiva y luego se llena
                publicarSinEnlace(p, contenido);
            }
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    private static void publicarSinEnlace(Path p, byte[] contenido) throws IOException {
        Files.createFile(p, soloDueno(p)); // falla con FileAlreadyExistsException si ya existe
        try {
            Files.write(p, contenido, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(p); // no queda un archivo a medias
            throw e;
        }
    }

    @Override
    public InputStream leer(String ruta) throws IOException {
        // El archivo final no se sigue si es un enlace simbólico (revisión Java, BAJO 4)
        return Files.newInputStream(dentro(resolver(ruta)), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public OutputStream escribir(String ruta) throws IOException {
        Path p = carpeta(resolver(ruta));
        Files.createFile(p, soloDueno(p)); // exclusivo y con 0600 desde que existe
        try {
            return Files.newOutputStream(p, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(p);
            throw e;
        }
    }

    @Override
    public void descartar(String ruta) {
        try {
            Files.deleteIfExists(dentro(resolver(ruta)));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("[almacen] No se pudo descartar un archivo: {}", e.getMessage());
        }
    }

    @Override
    public void verificar(String ruta, String sha256Esperado) throws IOException {
        dentro(resolver(ruta)); // ruta inválida → IllegalArgumentException antes de abrir nada
        Almacen.super.verificar(ruta, sha256Esperado);
    }
}
