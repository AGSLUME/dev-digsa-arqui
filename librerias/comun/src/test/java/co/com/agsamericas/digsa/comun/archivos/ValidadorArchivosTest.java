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

    // ---------------------------------------------------------------- revisión de seguridad de la integradora sobre VA1

    /** ZIP con las entradas dadas; {@code relleno} bytes pseudoaleatorios (incompresibles) en la primera, para forzar el tamaño. */
    private static byte[] zipConRelleno(int relleno, String... entradas) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(relleno + 4096);
        byte[] azar = new byte[relleno];
        new Random(7).nextBytes(azar);
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b)) {
            boolean primera = true;
            for (String e : entradas) {
                z.putNextEntry(new java.util.zip.ZipEntry(e));
                z.write(primera ? azar : "<x/>".getBytes(StandardCharsets.UTF_8));
                primera = false;
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    /**
     * SUPUESTO: la cabecera mínima de un MP4 (caja {@code ftyp} con marca {@code mp42}) basta para que Tika (solo por magia) diga
     * {@code video/mp4}; el resto son ceros hasta el tamaño pedido.
     */
    private static byte[] mp4(int tamano) {
        byte[] ftyp = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'};
        return Arrays.copyOf(ftyp, tamano);
    }

    @Test
    void unZipConEstructuraDeDocxDeMasDe25MbSeRechazaCon413SinAnalizarloComoOffice() throws IOException {
        byte[] grande = zipConRelleno((int) ValidadorArchivos.MAX_ARCHIVO + 1024 * 1024, "[Content_Types].xml", "word/document.xml");
        assertThat(grande.length).isGreaterThan((int) ValidadorArchivos.MAX_ARCHIVO);

        // El tamaño se mide antes de analizar: debe ser 413 (con el análisis primero sería un recorrido costoso del ZIP)
        assertThatThrownBy(() -> validador.validar("informe.docx", grande))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("El archivo «informe.docx» pesa más de 25 MB.");
                });
    }

    @Test
    void unArchivoDeTipoInadmitidoMuyGrandeTambienEs413() {
        byte[] exe = new byte[(int) ValidadorArchivos.MAX_ARCHIVO + 1];
        exe[0] = 'M';
        exe[1] = 'Z';

        assertThatThrownBy(() -> validador.validar("programa.exe", exe))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.estado()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void unVideoDe30MbPasaElTopeDeArchivoPeroUnoDeMasDe100MbNo() {
        byte[] treinta = mp4(30 * 1024 * 1024);
        ValidadorArchivos.Valido v = validador.validar("clip.mp4", treinta);
        assertThat(v.tipo()).isEqualTo("video/mp4");
        assertThat(v.extension()).isEqualTo("mp4");

        byte[] enorme = mp4((int) ValidadorArchivos.MAX_VIDEO + 1);
        assertThatThrownBy(() -> validador.validar("clip.mp4", enorme))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("El archivo «clip.mp4» pesa más de 100 MB.");
                });
    }

    @Test
    void elTopeDescomprimidoEs100Mb() {
        assertThat(ValidadorArchivos.MAX_DESCOMPRIMIDO).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    void unOle2GenericoYaNoSeAcepta() {
        byte[] ole = new byte[512];
        byte[] cabecera = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(cabecera, 0, ole, 0, cabecera.length);

        assertThat(ValidadorArchivos.TIPOS).doesNotContainKey("application/x-tika-msoffice");
        assertThatThrownBy(() -> validador.validar("x.bin", ole))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted("x.bin"));
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"word/vbaProject.bin", "word/activeX/activeX1.xml", "word/activeX/activeX1.bin"})
    void unDocxConMacrosOControlesActivosSeRechaza(String peligrosa) throws IOException {
        byte[] docx = zip("[Content_Types].xml", "_rels/.rels", "word/document.xml", peligrosa);

        assertThatThrownBy(() -> validador.validar("informe.docx", docx))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted("informe.docx"));
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"xl/vbaProject.bin", "xl/embeddings/oleObject1.bin", "xl/activeX/activeX1.xml"})
    void unXlsxConMacrosOIncrustadosSeRechaza(String peligrosa) throws IOException {
        byte[] xlsx = zip("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", peligrosa);

        assertThatThrownBy(() -> validador.validar("datos.xlsx", xlsx))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void unDocxYUnXlsxNormalesSiguenPasando() throws IOException {
        assertThat(validador.validar("informe.docx", zip("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")).extension())
                .isEqualTo("docx");
        assertThat(validador.validar("datos.xlsx", zip("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml")).extension())
                .isEqualTo("xlsx");
    }

    /**
     * SUPUESTO DEL CÓDIGO: el enderezado lee solo el segmento EXIF (JpegMetadataReader + ExifReader) y nunca el XMP, cuyo XML podría
     * traer entidades externas (XXE). La prueba mete un XMP con una entidad SYSTEM a /etc/passwd y comprueba que no se resuelve.
     */
    @Test
    void unXmpConEntidadExternaNoSeResuelveNiCuelga() throws IOException {
        String xml = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>&e;</dc:title></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>";
        byte[] cabecera = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] cuerpoXml = xml.getBytes(StandardCharsets.UTF_8);
        int longitud = 2 + cabecera.length + cuerpoXml.length;
        byte[] app1 = new byte[2 + longitud];
        app1[0] = (byte) 0xFF;
        app1[1] = (byte) 0xE1;
        app1[2] = (byte) (longitud >> 8);
        app1[3] = (byte) longitud;
        System.arraycopy(cabecera, 0, app1, 4, cabecera.length);
        System.arraycopy(cuerpoXml, 0, app1, 4 + cabecera.length, cuerpoXml.length);

        byte[] base = imagen("jpg", 40, 20);
        byte[] conXmp = new byte[base.length + app1.length];
        System.arraycopy(base, 0, conXmp, 0, 2);
        System.arraycopy(app1, 0, conXmp, 2, app1.length);
        System.arraycopy(base, 2, conXmp, 2 + app1.length, base.length - 2);
        byte[] jpeg = conOrientacionExif(conXmp, 6); // EXIF primero, XMP después

        ValidadorArchivos.Valido[] resultado = new ValidadorArchivos.Valido[1];
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2), () -> resultado[0] = validador.validar("foto.jpg", jpeg));

        assertThat(resultado[0].tipo()).isEqualTo("image/jpeg");
        assertThat(new String(resultado[0].contenido(), StandardCharsets.ISO_8859_1)).doesNotContain("root:");
        assertThat(ValidadorArchivos.dimensiones(resultado[0].contenido())).as("sigue enderezando con el XMP presente").containsExactly(20, 40);
    }

    // ---------------------------------------------------------------- tiposAdmitidos y OLE con macros (revisión de seguridad VA2)

    @Test
    void tiposAdmitidosEsUnMapaInmutableExtensionAContentType() {
        java.util.Map<String, String> t = ValidadorArchivos.tiposAdmitidos();

        assertThat(t).containsEntry("pdf", "application/pdf")
                .containsEntry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .containsEntry("jpg", "image/jpeg")
                .containsEntry("md", "text/markdown");
        assertThat(t.keySet()).containsExactlyInAnyOrderElementsOf(ValidadorArchivos.TIPOS.values());
        assertThat(t.values()).containsExactlyInAnyOrderElementsOf(ValidadorArchivos.TIPOS.keySet());
        assertThat(t.values()).noneMatch(v -> v.contains("x-tika"));
        assertThatThrownBy(() -> t.put("exe", "application/x-msdownload")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> t.remove("pdf")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(t::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    /** OLE2 con las entradas dadas; un nombre con "/" crea el directorio y la subentrada (p. ej. "Macros/VBA"). */
    private static byte[] ole(String... entradas) throws IOException {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            for (String e : entradas) {
                org.apache.poi.poifs.filesystem.DirectoryEntry dir = fs.getRoot();
                String[] partes = e.split("/");
                for (int i = 0; i < partes.length - 1; i++) {
                    dir = dir.hasEntry(partes[i])
                            ? (org.apache.poi.poifs.filesystem.DirectoryEntry) dir.getEntry(partes[i])
                            : dir.createDirectory(partes[i]);
                }
                dir.createDocument(partes[partes.length - 1], new java.io.ByteArrayInputStream(new byte[4096]));
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            fs.writeFilesystem(b);
            return b.toByteArray();
        }
    }

    @Test
    void unDocYUnXlsLimpiosSonDelTipoEsperadoYPasan() throws IOException {
        byte[] doc = ole("WordDocument", "1Table");
        byte[] xls = ole("Workbook");
        assertThat(validador.tipo(doc, "x.doc")).isEqualTo("application/msword");
        assertThat(validador.tipo(xls, "x.xls")).isEqualTo("application/vnd.ms-excel");

        assertThat(validador.validar("x.doc", doc).extension()).isEqualTo("doc");
        assertThat(validador.validar("x.xls", xls).extension()).isEqualTo("xls");
    }

    private void rechazaOleConEntradaPeligrosa(String nombre, String tipoEsperado, String... entradas) throws IOException {
        byte[] bytes = ole(entradas);
        // Precondición con Tika directo: tipo() ya aplica el filtro de macros y por eso no devuelve Word ni Excel para estos archivos
        assertThat(new org.apache.tika.Tika().detect(bytes, nombre)).as("precondición: Tika lo reconoce como " + tipoEsperado).isEqualTo(tipoEsperado);
        assertThatThrownBy(() -> validador.validar(nombre, bytes))
                .as(nombre + " con " + String.join(",", entradas))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).isEqualTo(NO_ADMITIDO.formatted(nombre));
                });
    }

    @Test
    void unDocConMacrosOConObjetosIncrustadosSeRechazaCon422() throws IOException {
        rechazaOleConEntradaPeligrosa("a.doc", "application/msword", "WordDocument", "Macros/VBA");
        rechazaOleConEntradaPeligrosa("b.doc", "application/msword", "WordDocument", "Macros");
        rechazaOleConEntradaPeligrosa("c.doc", "application/msword", "WordDocument", "ObjectPool/_1234567890");
    }

    @Test
    void unXlsConProyectoVbaOConObjetosIncrustadosSeRechazaCon422() throws IOException {
        rechazaOleConEntradaPeligrosa("d.xls", "application/vnd.ms-excel", "Workbook", "_VBA_PROJECT_CUR/VBA");
        rechazaOleConEntradaPeligrosa("e.xls", "application/vnd.ms-excel", "Workbook", "_VBA_PROJECT_CUR");
        rechazaOleConEntradaPeligrosa("f.xls", "application/vnd.ms-excel", "Workbook", "ObjectPool/_1234567890");
    }

    @Test
    void unOleDeMasDe25MbEs413SinAnalizarSusEntradas() throws IOException {
        byte[] grande = Arrays.copyOf(ole("WordDocument", "Macros/VBA"), (int) ValidadorArchivos.MAX_ARCHIVO + 1);

        // Con macros el análisis daría 422; el tope va antes y da 413
        assertThatThrownBy(() -> validador.validar("grande.doc", grande))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.estado()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("El archivo «grande.doc» pesa más de 25 MB.");
                });
    }

    private static String nombreMostrado(String mensaje) {
        int a = mensaje.indexOf('«');
        int b = mensaje.lastIndexOf('»');
        assertThat(a).isGreaterThanOrEqualTo(0);
        assertThat(b).isGreaterThan(a);
        return mensaje.substring(a + 1, b);
    }

    private static void sinControlesNiBidi(String texto) {
        assertThat(texto.chars().filter(c -> Character.isISOControl(c) || c == 0x202E)).isEmpty();
    }

    @Test
    void elNombreSeSaneaEnLosMensajesDeError() {
        String nombre = "a\u0000b\u001Fc‮" + "x".repeat(300);
        byte[] exe = {'M', 'Z', 0, 0};
        byte[] grande = Arrays.copyOf(pdfMinimo(), (int) ValidadorArchivos.MAX_ARCHIVO + 1);

        for (Runnable caso : new Runnable[] {
                () -> validador.validar(nombre, new byte[0]),
                () -> validador.validar(nombre, exe),
                () -> validador.validar(nombre, grande)}) {
            assertThatThrownBy(caso::run).isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                sinControlesNiBidi(e.getMessage());
                String mostrado = nombreMostrado(e.getMessage());
                assertThat(mostrado.length()).isLessThanOrEqualTo(100);
                assertThat(mostrado).startsWith("a").endsWith("…");
            });
        }
    }

    @Test
    void unNombreCortoYNormalNoSeAltera() {
        assertThatThrownBy(() -> validador.validar("informe final.pdf", new byte[0]))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.getMessage()).isEqualTo("El archivo «informe final.pdf» está vacío."));
    }
}
