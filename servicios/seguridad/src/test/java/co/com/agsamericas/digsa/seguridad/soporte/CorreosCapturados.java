package co.com.agsamericas.digsa.seguridad.soporte;

import co.com.agsamericas.digsa.seguridad.correo.CorreoSeguridad;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doble del puerto de correo para las pruebas: guarda lo enviado (con el secreto, para que la prueba lo lea como lo leería el usuario)
 * y puede simular que el siguiente envío falla. Reemplaza al adaptador real en {@link PruebaIntegracion}.
 */
public class CorreosCapturados implements CorreoSeguridad {

    private static final Pattern CODIGO = Pattern.compile(">(\\d{6})<");
    private static final Pattern ENLACE = Pattern.compile("definir-clave\\?tkn=([a-f0-9]{64})");

    private final List<Envio> enviados = new ArrayList<>();
    private final AtomicBoolean fallarSiguiente = new AtomicBoolean();

    @Override
    public synchronized Resultado enviar(Envio envio) {
        if (fallarSiguiente.getAndSet(false)) {
            return Resultado.fallo("SMTP simulado caído");
        }
        enviados.add(envio);
        return Resultado.bien();
    }

    /** El siguiente envío falla (una sola vez). */
    public void fallarSiguiente() {
        fallarSiguiente.set(true);
    }

    public synchronized List<Envio> enviados() {
        return List.copyOf(enviados);
    }

    public synchronized List<Envio> a(String correo) {
        return enviados.stream().filter(e -> e.para().equals(correo)).toList();
    }

    /** El código de 6 dígitos del último correo MFA enviado a {@code correo}. */
    public synchronized String ultimoCodigo(String correo) {
        return ultimo(correo, "mfa_codigo", CODIGO).orElseThrow(() -> new AssertionError("No hay código para " + correo));
    }

    /** El token (64 hex) del último enlace para definir la contraseña enviado a {@code correo}. */
    public synchronized String ultimoEnlace(String correo) {
        return ultimo(correo, "clave_enlace", ENLACE).orElseThrow(() -> new AssertionError("No hay enlace para " + correo));
    }

    public synchronized void limpiar() {
        enviados.clear();
        fallarSiguiente.set(false);
    }

    private Optional<String> ultimo(String correo, String plantilla, Pattern p) {
        for (int i = enviados.size() - 1; i >= 0; i--) {
            Envio e = enviados.get(i);
            if (e.para().equals(correo) && e.plantilla().equals(plantilla)) {
                Matcher m = p.matcher(e.html());
                return m.find() ? Optional.of(m.group(1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
