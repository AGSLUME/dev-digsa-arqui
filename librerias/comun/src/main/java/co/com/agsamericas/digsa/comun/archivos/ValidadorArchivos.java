package co.com.agsamericas.digsa.comun.archivos;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;

/**
 * Valida un archivo por su contenido (nunca por la extensión ni por lo que diga el navegador), aplica el tope de tamaño y corrige la
 * orientación EXIF de las fotos (decisión 10.7). Portado de {@code plataforma.ValidadorArchivos} de digsa-java (develop @ 1ff82de);
 * referencia: docs/especificaciones/archivos del monolito. Lo registra {@link ConfiguracionArchivos}.
 */
public class ValidadorArchivos {

    public static final long MAX_ARCHIVO = 25L * 1024 * 1024;
    public static final long MAX_VIDEO = 100L * 1024 * 1024;
    public static final int MAX_POR_PETICION = 20;

    /** Tipo real admitido → extensión con que se guarda. */
    static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("image/heic", "heic"), Map.entry("image/heif", "heif"), Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            // Sin el genérico application/x-tika-msoffice (el monolito lo guardaba como «doc»): solo Word o Excel reconocidos (seguridad, MEDIA 2)
            Map.entry("application/msword", "doc"), Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("video/mp4", "mp4"), Map.entry("video/quicktime", "mov"), Map.entry("video/webm", "webm"), Map.entry("video/3gpp", "3gp"),
            Map.entry("text/plain", "txt"), Map.entry("text/markdown", "md"));

    /** Archivo validado, listo para guardar. */
    public record Valido(byte[] contenido, String tipo, String extension, String sha256) {
    }

    private final Tika tika = new Tika();

    public Valido validar(String nombre, byte[] contenido) {
        String mostrar = paraMostrar(nombre);
        if (contenido == null || contenido.length == 0) {
            throw ErrorNegocio.validacion("El archivo «" + mostrar + "» está vacío.");
        }
        // El tope se aplica ANTES de analizar el contenido (seguridad, MEDIA 1): la detección por cabecera ya dice si es video; un ZIP de
        // más de 25 MB no se llega a descomprimir.
        long tope = tika.detect(contenido).startsWith("video/") ? MAX_VIDEO : MAX_ARCHIVO;
        if (contenido.length > tope) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo «" + mostrar + "» pesa más de " + tope / (1024 * 1024) + " MB.");
        }
        String tipo = tipo(contenido, nombre);
        String extension = TIPOS.get(tipo);
        if (extension == null) {
            throw ErrorNegocio.validacion("El archivo «" + mostrar + "» no es de un tipo admitido (fotos, PDF, Word, Excel, video o texto).");
        }
        byte[] guardar = "image/jpeg".equals(tipo) ? enderezar(contenido) : contenido;
        return new Valido(guardar, tipo, extension, co.com.agsamericas.digsa.reglas.Huellas.sha256(guardar));
    }

    /**
     * Tipo real por el contenido. El nombre solo afina dentro de la familia que dicen los bytes (un ZIP que es .docx o .xlsx, un OLE
     * que es .doc o .xls, un texto que es .md); nunca convierte en PDF o foto algo cuyos bytes no lo son.
     */
    String tipo(byte[] contenido, String nombre) {
        String porBytes = tika.detect(contenido);
        String n = nombre == null ? "" : nombre;
        return switch (porBytes) {
            case "application/zip", "application/x-tika-ooxml" -> {
                String t = tika.detect(contenido, n);
                // El nombre solo dice qué tipo de Office sería; la estructura del ZIP lo confirma (revisión Java M4)
                yield t.startsWith("application/vnd.openxmlformats-officedocument.") && estructuraOffice(contenido, t) ? t : "application/zip";
            }
            case "application/x-tika-msoffice" -> {
                String t = tika.detect(contenido, n);
                yield "application/msword".equals(t) || "application/vnd.ms-excel".equals(t) ? t : porBytes;
            }
            case "text/plain" -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".md") ? "text/markdown" : porBytes;
            default -> porBytes;
        };
    }

    static final int MAX_NOMBRE_MOSTRADO = 100;

    /**
     * El nombre que subió el usuario, tal como se muestra en un mensaje (seguridad, BAJA 5): sin caracteres de control ni de formato
     * (p. ej. U+202E, que invierte el texto) y cortado a {@link #MAX_NOMBRE_MOSTRADO} caracteres. Nunca se usa para guardar.
     */
    static String paraMostrar(String nombre) {
        if (nombre == null) {
            return "";
        }
        // Control, formato (U+202E…), separadores de línea y párrafo, uso privado y sin asignar (revisión Java de VA2.S, BAJO 8)
        String limpio = nombre.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Co}\\p{Cn}]", "");
        if (limpio.codePointCount(0, limpio.length()) <= MAX_NOMBRE_MOSTRADO) {
            return limpio;
        }
        return new String(limpio.codePoints().limit(MAX_NOMBRE_MOSTRADO - 1).toArray(), 0, MAX_NOMBRE_MOSTRADO - 1) + "…";
    }

    /** Ancho y alto declarados de una imagen sin decodificarla (null si no es una imagen legible). */
    public static int[] dimensiones(byte[] imagen) {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(imagen))) {
            java.util.Iterator<javax.imageio.ImageReader> lectores = ImageIO.getImageReaders(in);
            if (!lectores.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader r = lectores.next();
            try {
                r.setInput(in, true, true);
                return new int[] {r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Entradas y bytes descomprimidos que se revisan, como máximo, para confirmar un documento de Office (evita las bombas ZIP). */
    static final int MAX_ENTRADAS_ZIP = 5_000;
    static final long MAX_DESCOMPRIMIDO = 100L * 1024 * 1024;

    /**
     * ¿El ZIP tiene la estructura de un docx o un xlsx? {@code [Content_Types].xml} y la carpeta {@code word/} o {@code xl/}, sin macros
     * ({@code vbaProject.bin}) ni objetos activos o incrustados. Lo que no se pueda leer, o pase los topes de entradas o de tamaño
     * descomprimido, no lo es.
     */
    static boolean estructuraOffice(byte[] zip, String tipo) {
        String carpeta = tipo.contains("wordprocessingml") ? "word/" : tipo.contains("spreadsheetml") ? "xl/" : null;
        if (carpeta == null) {
            return false;
        }
        boolean tipos = false;
        boolean partes = false;
        long total = 0;
        byte[] buf = new byte[8192];
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            int entradas = 0;
            while ((e = in.getNextEntry()) != null) {
                if (++entradas > MAX_ENTRADAS_ZIP) {
                    return false;
                }
                String n = e.getName();
                tipos |= "[Content_Types].xml".equals(n);
                partes |= n.replace('\\', '/').toLowerCase(java.util.Locale.ROOT).startsWith(carpeta);
                // Los nombres de las partes OPC no distinguen mayúsculas: se comparan en minúsculas y con «/» (revisión Java de VA2.S, M3)
                String parte = n.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
                if (parte.endsWith("vbaproject.bin") || parte.contains("/activex/") || parte.contains("/embeddings/")
                        || parte.contains("/externallinks/") || parte.contains("oleobject")) {
                    return false; // macros u objetos activos: no se admiten (seguridad, MEDIA 2; el monolito no lo revisaba)
                }
                for (int r; (r = in.read(buf)) > 0; ) {
                    total += r;
                    if (total > MAX_DESCOMPRIMIDO) {
                        return false;
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return tipos && partes;
    }

    /**
     * Más píxeles que esto no se decodifican para enderezar: una foto de 16 MP ocupa unos 64 MB al decodificarla y otro tanto la girada
     * (revisión Java M3; el monolito admitía 40 MP). Además, como máximo {@link #ENDEREZANDO} fotos a la vez.
     */
    static final long MAX_PIXELES = 16_000_000L;
    private static final java.util.concurrent.Semaphore ENDEREZANDO = new java.util.concurrent.Semaphore(2);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ValidadorArchivos.class);

    /** Aplica la orientación EXIF a la foto (las de celular vienen giradas). Si no se puede, la deja como llegó. */
    static byte[] enderezar(byte[] jpeg) {
        boolean turno = false;
        try {
            turno = ENDEREZANDO.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!turno) {
                LOG.info("[archivos] Foto sin enderezar: demasiadas fotos a la vez");
                return jpeg;
            }
            return enderezarConTurno(jpeg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jpeg;
        } finally {
            if (turno) {
                ENDEREZANDO.release();
            }
        }
    }

    private static byte[] enderezarConTurno(byte[] jpeg) {
        int[] dim = dimensiones(jpeg);
        if (dim == null || (long) dim[0] * dim[1] > MAX_PIXELES) {
            LOG.info("[archivos] Foto sin enderezar: dimensiones {} fuera del tope", dim == null ? "ilegibles" : dim[0] + "x" + dim[1]);
            return jpeg;
        }
        try {
            // Solo el segmento EXIF: el XMP (XML) nunca se analiza, así no hay XXE posible (seguridad, BAJA 3)
            Metadata md = com.drew.imaging.jpeg.JpegMetadataReader.readMetadata(new ByteArrayInputStream(jpeg),
                    java.util.List.of(new com.drew.metadata.exif.ExifReader()));
            ExifIFD0Directory d = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (d == null || !d.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return jpeg;
            }
            int o = d.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            if (o <= 1 || o > 8) {
                return jpeg;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                return jpeg;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            boolean gira = o >= 5;
            // x' = m00 x + m01 y + m02; y' = m10 x + m11 y + m12 (orientaciones EXIF 2 a 8; de la 5 a la 8 se cruzan ancho y alto)
            AffineTransform t = switch (o) {
                case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
                case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
                case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
                case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
                case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
                default -> new AffineTransform(0, -1, 1, 0, 0, w);
            };
            BufferedImage out = new BufferedImage(gira ? h : w, gira ? w : h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(img, t, null);
            g.dispose();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", b);
            return b.toByteArray(); // la imagen nueva ya no lleva la etiqueta de orientación
        } catch (IOException | RuntimeException | com.drew.imaging.ImageProcessingException | com.drew.metadata.MetadataException e) {
            LOG.info("[archivos] Foto sin enderezar ({}): se guarda como llegó", e.getClass().getSimpleName());
            return jpeg;
        }
    }
}
