package co.com.agsamericas.digsa.comun.archivos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.Huellas;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** VA1.1: almacén en disco (reglas del AlmacenLocal del monolito) e integridad SHA-256 (decisión D3). */
class AlmacenDiscoTest {

    @TempDir
    Path raiz;

    AlmacenDisco almacen;

    @BeforeEach
    void preparar() {
        almacen = new AlmacenDisco(raiz);
    }

    @Test
    void guardaYLeeCreandoCarpetasIntermedias() throws IOException {
        byte[] datos = "contenido".getBytes(StandardCharsets.UTF_8);

        almacen.guardar("solicitudes/2026/11/abc_1-2.pdf", datos);

        assertThat(Files.readAllBytes(raiz.resolve("solicitudes/2026/11/abc_1-2.pdf"))).isEqualTo(datos);
        try (InputStream in = almacen.leer("solicitudes/2026/11/abc_1-2.pdf")) {
            assertThat(in.readAllBytes()).isEqualTo(datos);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"../fuera.pdf", "a/../../fuera.pdf", "a/..b.pdf", "/etc/passwd.txt", "/abs.pdf", "sin_extension", "con espacio.pdf",
            "carpeta con espacio/a.pdf", "mayuscula.PDF", "a.Pdf", "a\\b.pdf", "C:\\x.pdf", "", "a.", "tilde~.pdf", "acentó.pdf"})
    void rechazaRutasInvalidas(String ruta) {
        assertThatThrownBy(() -> almacen.guardar(ruta, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> almacen.leer(ruta)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> almacen.escribir(ruta)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rutaInvalidaNoDejaNadaEnLaRaiz() throws IOException {
        assertThatThrownBy(() -> almacen.guardar("../fuera.pdf", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(raiz.getParent().resolve("fuera.pdf"))).isFalse();
        try (var lista = Files.list(raiz)) {
            assertThat(lista).isEmpty();
        }
    }

    @Test
    void noSobrescribeConGuardar() throws IOException {
        almacen.guardar("a/x.txt", "uno".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> almacen.guardar("a/x.txt", "dos".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(raiz.resolve("a/x.txt"))).isEqualTo("uno");
    }

    @Test
    void escribeEnFlujoYNoSobrescribe() throws IOException {
        try (OutputStream out = almacen.escribir("videos/v1.mp4")) {
            out.write(new byte[] {1, 2, 3});
        }

        assertThat(Files.readAllBytes(raiz.resolve("videos/v1.mp4"))).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> almacen.escribir("videos/v1.mp4")).isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void leerArchivoInexistenteLanzaNoSuchFile() {
        assertThatThrownBy(() -> almacen.leer("no/existe.pdf")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void descartarBorraElArchivo() throws IOException {
        almacen.guardar("a/b.pdf", new byte[] {1});

        almacen.descartar("a/b.pdf");

        assertThat(Files.exists(raiz.resolve("a/b.pdf"))).isFalse();
    }

    @Test
    void descartarNuncaLanzaConRutaInexistenteInvalidaONula() {
        assertThatCode(() -> almacen.descartar("no/existe.pdf")).doesNotThrowAnyException();
        assertThatCode(() -> almacen.descartar("../fuera.pdf")).doesNotThrowAnyException();
        assertThatCode(() -> almacen.descartar("/abs.pdf")).doesNotThrowAnyException();
        assertThatCode(() -> almacen.descartar(null)).doesNotThrowAnyException();
    }

    @Test
    void descartarNoBorraArchivosFueraDeLaRaiz() throws IOException {
        Path fuera = Files.createTempFile(raiz.getParent(), "fuera", ".pdf");
        try {
            almacen.descartar("../" + fuera.getFileName());

            assertThat(fuera).exists();
        } finally {
            Files.deleteIfExists(fuera);
        }
    }

    // ---- integridad (D3) ----

    @Test
    void verificarConHashCorrectoNoLanza() throws IOException {
        byte[] datos = "escrito de la solicitud".getBytes(StandardCharsets.UTF_8);
        almacen.guardar("s/1.pdf", datos);

        assertThatCode(() -> almacen.verificar("s/1.pdf", Huellas.sha256(datos))).doesNotThrowAnyException();
    }

    @Test
    void verificarConArchivoAlteradoEnDiscoLanzaArchivoAlterado() throws IOException {
        byte[] datos = "original".getBytes(StandardCharsets.UTF_8);
        almacen.guardar("s/2.pdf", datos);
        String huella = Huellas.sha256(datos);
        Files.write(raiz.resolve("s/2.pdf"), "alterado".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> almacen.verificar("s/2.pdf", huella))
                .isInstanceOf(ArchivoAlterado.class)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("s/2.pdf")
                .hasMessageNotContaining(huella);
    }

    @Test
    void verificarConHashEsperadoDistintoLanzaArchivoAlterado() throws IOException {
        almacen.guardar("s/3.pdf", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> almacen.verificar("s/3.pdf", Huellas.sha256("otro"))).isInstanceOf(ArchivoAlterado.class);
    }

    @Test
    void verificarConArchivoFaltanteLanzaNoSuchFile() {
        assertThatThrownBy(() -> almacen.verificar("s/falta.pdf", Huellas.sha256("x"))).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void verificarConRutaInvalidaLanzaIllegalArgument() {
        assertThatThrownBy(() -> almacen.verificar("../x.pdf", Huellas.sha256("x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verificarPorDefectoDeLaInterfazUsaElFlujoDeLectura() {
        byte[] datos = "en memoria".getBytes(StandardCharsets.UTF_8);
        Almacen enMemoria = new Almacen() {
            @Override
            public void guardar(String ruta, byte[] contenido) {
            }

            @Override
            public InputStream leer(String ruta) {
                return new ByteArrayInputStream(datos);
            }

            @Override
            public OutputStream escribir(String ruta) {
                return new ByteArrayOutputStream();
            }

            @Override
            public void descartar(String ruta) {
            }
        };

        assertThatCode(() -> enMemoria.verificar("m/1.txt", Huellas.sha256(datos))).doesNotThrowAnyException();
        assertThatThrownBy(() -> enMemoria.verificar("m/1.txt", Huellas.sha256("distinto"))).isInstanceOf(ArchivoAlterado.class);
    }

    // ---------------------------------------------------------------- revisión de seguridad de la integradora sobre VA1

    @Test
    void guardarNoSobrescribeYDejaIntactoElOriginalSinTemporales() throws IOException {
        almacen.guardar("a/x.txt", "uno".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> almacen.guardar("a/x.txt", "dos".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(raiz.resolve("a/x.txt"))).isEqualTo("uno");
        try (var lista = Files.list(raiz.resolve("a"))) {
            assertThat(lista.map(p -> p.getFileName().toString())).containsExactly("x.txt");
        }
    }

    @Test
    void despuesDeGuardarNoQuedaNingunTemporalEnLaCarpeta() throws IOException {
        almacen.guardar("t/a.pdf", new byte[] {1, 2, 3});
        almacen.guardar("t/b.pdf", new byte[] {4});

        try (var lista = Files.list(raiz.resolve("t"))) {
            assertThat(lista.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("a.pdf", "b.pdf");
        }
    }

    @Test
    void elArchivoGuardadoEsSoloDelDuenoEnPosix() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        almacen.guardar("p/a.pdf", new byte[] {1});

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(raiz.resolve("p/a.pdf"))))
                .isEqualTo("rw-------");
    }

    @Test
    void unEnlaceDeCarpetaQueApuntaFueraDeLaRaizSeRechaza() throws IOException {
        Path fuera = Files.createTempDirectory("fuera-del-almacen");
        try {
            Files.writeString(fuera.resolve("secreto.txt"), "no debe leerse");
            try {
                Files.createSymbolicLink(raiz.resolve("enlace"), fuera);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "No se pueden crear enlaces simbólicos aquí: " + e);
            }

            assertThatThrownBy(() -> almacen.guardar("enlace/nuevo.txt", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> almacen.leer("enlace/secreto.txt")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> almacen.escribir("enlace/otro.txt")).isInstanceOf(IllegalArgumentException.class);
            assertThat(fuera.resolve("nuevo.txt")).doesNotExist();
            assertThat(fuera.resolve("otro.txt")).doesNotExist();
        } finally {
            try (var lista = Files.list(fuera)) {
                lista.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(fuera);
        }
    }

    @Test
    void unaRutaDeMasDe200CaracteresSeRechaza() {
        String larga = "a".repeat(197) + ".pdf"; // 201
        String enElLimite = "a".repeat(196) + ".pdf"; // 200

        assertThatThrownBy(() -> almacen.guardar(larga, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> almacen.leer(larga)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> almacen.escribir(larga)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> almacen.guardar(enElLimite, new byte[] {1})).doesNotThrowAnyException();
    }
}
