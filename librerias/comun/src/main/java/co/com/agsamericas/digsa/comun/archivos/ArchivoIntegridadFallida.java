package co.com.agsamericas.digsa.comun.archivos;

/**
 * Evento: un archivo del almacén no coincide con su huella SHA-256 o falta (exportación, descarga de soportes), como en el monolito. Lo
 * convierte en alerta crítica {@code integridad_archivo} (GMI P77 b) quien la publique hacia {@code notificaciones}. El detalle no lleva
 * datos personales: solo identificadores y conteos.
 */
public record ArchivoIntegridadFallida(String detalle) {
}
