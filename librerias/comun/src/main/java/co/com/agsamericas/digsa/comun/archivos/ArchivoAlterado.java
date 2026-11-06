package co.com.agsamericas.digsa.comun.archivos;

/** Un archivo del almacén ya no coincide con su huella SHA-256. El mensaje solo lleva la ruta (nombre aleatorio, sin datos personales). */
public class ArchivoAlterado extends RuntimeException {

    private final String ruta;

    public ArchivoAlterado(String ruta) {
        super("El archivo " + ruta + " no coincide con su huella SHA-256.");
        this.ruta = ruta;
    }

    public String ruta() {
        return ruta;
    }
}
