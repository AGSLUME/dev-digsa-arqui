package co.com.agsamericas.digsa.comun.archivos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.comun.errores.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Huellas;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * VA1.1: validador por contenido portado del monolito (docs/especificaciones/archivos). El monolito no tenía pruebas de este
 * componente; estas son nuevas.
 */
class ValidadorArchivosTest {

    private static final String NO_ADMITIDO = "El archivo «%s» no es de un tipo admitido (fotos, PDF, Word, Excel, video o texto).";

    private final ValidadorArchivos validador = new ValidadorArchivos();

    private static byte[] pdfMinimo() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] imagen(String formato, int ancho, int alto) throws IOException {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, formato, b);
        return b.toByteArray();
    }

    /** Inserta tras el SOI un segmento APP1 EXIF (big-endian) con solo la etiqueta Orientation (0x0112). */
    private static byte[] conOrientacionExif(byte[] jpeg, int orientacion) {
        byte[] app1 = {
            (byte) 0xFF, (byte) 0xE1, 0x00, 0x22, // marcador APP1 y longitud 34
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00, // "Exif\0\0"
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // cabecera TIFF "MM", IFD0 en el desplazamiento 8
            0x00, 0x01, // una entrada
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientacion, 0x00, 0x00, // Orientation, SHORT
            0x00, 0x00, 0x00, 0x00 // sin IFD siguiente
        };
        byte[] salida = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, salida, 0, 2); // SOI
        System.arraycopy(app1, 0, salida, 2, app1.length);
        System.arraycopy(jpeg, 2, salida, 2 + app1.length, jpeg.length - 2);
        return salida;
    }

    @Test
    void pdfMinimoSeAceptaPorContenido() {
        byte[] pdf = pdfMinimo();

        ValidadorArchivos.Valido v = validador.validar("escrito.pdf", pdf);

        assertThat(v.tipo()).isEqualTo("application/pdf");
        assertThat(v.extension()).isEqualTo("pdf");
        assertThat(v.sha256()).matches("[0-9a-f]{64}").isEqualTo(Huellas.sha256(pdf));
        assertThat(v.contenido()).isEqualTo(pdf);
    }

    @Test
    void pdfRenombradoComoJpgSigueSiendoPdf() {
        ValidadorArchivos.Valido v = validador.validar("foto.jpg", pdfMinimo());

        assertThat(v.tipo()).isEqualTo("application/pdf");
        assertThat(v.extension()).isEqualTo("pdf");
    }

    @Test
    void ejecutableRenombradoComoPdfSeRechazaCon422() {
        byte[] exe = new byte[256];
        exe[0] = 'M';
        exe[1] = 'Z';

        assertThatThrownBy(() -> validador.validar("factura.pdf", exe))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted("factura.pdf"));
                });
    }

    @Test
    void htmlRenombradoComoJpgSeRechazaCon422() {
        byte[] html = "<html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validador.validar("foto.jpg", html))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted("foto.jpg"));
                });
    }

    @Test
    void bytesAleatoriosSeRechazanCon422() {
        byte[] azar = new byte[512];
        new Random(42).nextBytes(azar);
        azar[0] = (byte) 0x80; // evita cualquier cabecera reconocible

        assertThatThrownBy(() -> validador.validar("datos.pdf", azar))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted("datos.pdf"));
                });
    }

    @Test
    void vacioYNuloSeRechazanCon422() {
        assertThatThrownBy(() -> validador.validar("vacio.pdf", new byte[0]))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo("El archivo «vacio.pdf» está vacío.");
                });
        assertThatThrownBy(() -> validador.validar("nulo.pdf", null))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.getMessage()).isEqualTo("El archivo «nulo.pdf» está vacío."));
    }

    @Test
    void masDe25MbSeRechazaCon413() {
        byte[] grande = Arrays.copyOf(pdfMinimo(), (int) ValidadorArchivos.MAX_ARCHIVO + 1);

        assertThatThrownBy(() -> validador.validar("grande.pdf", grande))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("El archivo «grande.pdf» pesa más de 25 MB.");
                });
    }

    @Test
    void exactamente25MbSeAcepta() {
        byte[] justo = Arrays.copyOf(pdfMinimo(), (int) ValidadorArchivos.MAX_ARCHIVO);

        assertThat(validador.validar("justo.pdf", justo).tipo()).isEqualTo("application/pdf");
    }

    @Test
    void constantesDelMonolito() {
        assertThat(ValidadorArchivos.MAX_ARCHIVO).isEqualTo(25L * 1024 * 1024);
        assertThat(ValidadorArchivos.MAX_VIDEO).isEqualTo(100L * 1024 * 1024);
        assertThat(ValidadorArchivos.MAX_POR_PETICION).isEqualTo(20);
    }

    @Test
    void textoMdEsMarkdownYTxtEsTextoPlano() {
        byte[] md = "# Título\n\nUn párrafo de prueba.\n".getBytes(StandardCharsets.UTF_8);

        ValidadorArchivos.Valido v = validador.validar("notas.md", md);
        ValidadorArchivos.Valido t = validador.validar("notas.txt", md);

        assertThat(v.tipo()).isEqualTo("text/markdown");
        assertThat(v.extension()).isEqualTo("md");
        assertThat(t.tipo()).isEqualTo("text/plain");
        assertThat(t.extension()).isEqualTo("txt");
    }

    @Test
    void tipoUsaElContenidoYElNombreSoloAfinaDentroDeLaFamilia() {
        assertThat(validador.tipo(pdfMinimo(), "x.md")).isEqualTo("application/pdf");
        assertThat(validador.tipo("hola".getBytes(StandardCharsets.UTF_8), null)).isEqualTo("text/plain");
    }

    @Test
    void jpegSeAceptaPorContenidoAunqueSeLlamePng() throws IOException {
        ValidadorArchivos.Valido v = validador.validar("foto.png", imagen("jpg", 10, 10));

        assertThat(v.tipo()).isEqualTo("image/jpeg");
        assertThat(v.extension()).isEqualTo("jpg");
    }

    @Test
    void jpegSinExifQuedaIgual() throws IOException {
        byte[] jpeg = imagen("jpg", 40, 20);

        assertThat(ValidadorArchivos.enderezar(jpeg)).isEqualTo(jpeg);
        assertThat(validador.validar("a.jpg", jpeg).contenido()).isEqualTo(jpeg);
    }

    @Test
    void jpegConExifOrientacion6SeEnderezaCruzandoAnchoYAlto() throws IOException {
        byte[] girado = conOrientacionExif(imagen("jpg", 40, 20), 6);
        assertThat(ValidadorArchivos.dimensiones(girado)).containsExactly(40, 20);

        byte[] derecho = ValidadorArchivos.enderezar(girado);

        assertThat(ValidadorArchivos.dimensiones(derecho)).containsExactly(20, 40);
        assertThat(validador.validar("celular.jpg", girado).contenido()).isEqualTo(derecho);
    }

    @Test
    void jpegConExifOrientacion3MantieneDimensionesYCambiaLosBytes() throws IOException {
        byte[] girado = conOrientacionExif(imagen("jpg", 40, 20), 3);

        byte[] derecho = ValidadorArchivos.enderezar(girado);

        assertThat(ValidadorArchivos.dimensiones(derecho)).containsExactly(40, 20);
        assertThat(derecho).isNotEqualTo(girado);
    }

    @Test
    void jpegConOrientacion1NoSeToca() throws IOException {
        byte[] jpeg = conOrientacionExif(imagen("jpg", 40, 20), 1);

        assertThat(ValidadorArchivos.enderezar(jpeg)).isEqualTo(jpeg);
    }

    @Test
    void enderezarDejaIgualLoQueNoEsUnaImagenLegible() {
        byte[] basura = {1, 2, 3, 4};

        assertThat(ValidadorArchivos.enderezar(basura)).isEqualTo(basura);
    }

    @Test
    void dimensionesDeUnPng() throws IOException {
        assertThat(ValidadorArchivos.dimensiones(imagen("png", 30, 50))).containsExactly(30, 50);
    }

    @Test
    void dimensionesDeAlgoQueNoEsImagenEsNulo() {
        assertThat(ValidadorArchivos.dimensiones("texto".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ValidadorArchivos.dimensiones(pdfMinimo())).isNull();
    }
    // ---------------------------------------------------------------- documentos de Office (revisión Java M4)

    private static byte[] zip(String... entradas) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b)) {
            for (String e : entradas) {
                z.putNextEntry(new java.util.zip.ZipEntry(e));
                z.write("<x/>".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    @Test
    void unZipCualquieraLlamadoDocxNoEsUnWord() throws IOException {
        byte[] falso = zip("leeme.txt", "bin/programa.sh");
        assertThatThrownBy(() -> validador.validar("informe.docx", falso)).isInstanceOf(ErrorNegocio.class)
                .hasMessage(NO_ADMITIDO.formatted("informe.docx"));
        assertThatThrownBy(() -> validador.validar("tabla.xlsx", falso)).isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void unDocxConSuEstructuraSeAcepta() throws IOException {
        byte[] docx = zip("[Content_Types].xml", "_rels/.rels", "word/document.xml");
        ValidadorArchivos.Valido v = validador.validar("informe.docx", docx);
        assertThat(v.extension()).isEqualTo("docx");
        assertThat(ValidadorArchivos.estructuraOffice(docx, v.tipo())).isTrue();
    }

    @Test
    void unWordNoPasaPorExcelNiAlReves() throws IOException {
        byte[] docx = zip("[Content_Types].xml", "word/document.xml");
        assertThat(ValidadorArchivos.estructuraOffice(docx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isFalse();
        byte[] xlsx = zip("[Content_Types].xml", "xl/workbook.xml");
        assertThat(validador.validar("datos.xlsx", xlsx).extension()).isEqualTo("xlsx");
        assertThatThrownBy(() -> validador.validar("datos.docx", xlsx)).isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void unZipQueSeDescomprimeDemasiadoNoEsOffice() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b)) {
            z.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            byte[] ceros = new byte[1024 * 1024];
            for (int i = 0; i <= ValidadorArchivos.MAX_DESCOMPRIMIDO / ceros.length; i++) {
                z.write(ceros);
            }
            z.closeEntry();
        }
        assertThat(ValidadorArchivos.estructuraOffice(b.toByteArray(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isFalse();
    }
}