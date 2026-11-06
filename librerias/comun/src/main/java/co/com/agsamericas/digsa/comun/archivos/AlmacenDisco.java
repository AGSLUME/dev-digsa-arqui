package co.com.agsamericas.digsa.comun.archivos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archivos en una carpeta (en local y en Docker, el volumen del servicio; ADR-005), fuera de cualquier carpeta web. Igual que
 * {@code plataforma.AlmacenLocal} del monolito: rutas relativas con nombre aleatorio, nunca sobrescribe y nunca sale de la raíz.
 */
public class AlmacenDisco implements Almacen {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenDisco.class);
    /** Ruta relativa segura: sin «..», sin rutas absolutas y con extensión (spec archivos). */
    static final Pattern RUTA = Pattern.compile("^[A-Za-z0-9_/-]+\\.[a-z0-9]+$");

    private final Path raiz;

    public AlmacenDisco(Path raiz) {
        this.raiz = raiz.toAbsolutePath().normalize();
    }

    private Path resolver(String ruta) {
        if (ruta == null || !RUTA.matcher(ruta).matches() || ruta.contains("..") || ruta.startsWith("/")) {
            throw new IllegalArgumentException("Ruta de archivo no válida");
        }
        Path p = raiz.resolve(ruta).normalize();
        if (!p.startsWith(raiz)) {
            throw new IllegalArgumentException("Ruta de archivo fuera del almacén");
        }
        return p;
    }

    @Override
    public void guardar(String ruta, byte[] contenido) throws IOException {
        Path p = resolver(ruta);
        Files.createDirectories(p.getParent());
        try {
            Files.write(p, contenido, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw e; // nunca se toca un archivo que ya existía
        } catch (IOException e) {
            // Falló a medias (p. ej. disco lleno): no queda un archivo parcial que bloquee el reintento (revisión Java B7)
            Files.deleteIfExists(p);
            throw e;
        }
    }

    @Override
    public InputStream leer(String ruta) throws IOException {
        return Files.newInputStream(resolver(ruta));
    }

    @Override
    public OutputStream escribir(String ruta) throws IOException {
        Path p = resolver(ruta);
        Files.createDirectories(p.getParent());
        return Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void descartar(String ruta) {
        try {
            Files.deleteIfExists(resolver(ruta));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("[almacen] No se pudo descartar un archivo: {}", e.getMessage());
        }
    }

    @Override
    public void verificar(String ruta, String sha256Esperado) throws IOException {
        resolver(ruta); // ruta inválida → IllegalArgumentException antes de abrir nada
        Almacen.super.verificar(ruta, sha256Esperado);
    }
}
