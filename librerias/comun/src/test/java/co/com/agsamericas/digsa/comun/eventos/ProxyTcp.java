package co.com.agsamericas.digsa.comun.eventos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy TCP mínimo entre la aplicación de prueba y RabbitMQ. {@link #caer()} corta las conexiones vivas y deja de escuchar (el puerto
 * rechaza conexiones, como un broker caído); {@link #reponer()} vuelve a escuchar en el mismo puerto. Así la prueba simula el broker
 * caído sin detener el contenedor (cuyo puerto cambiaría al reiniciarlo).
 */
final class ProxyTcp {

    private final String hostDestino;
    private final int puertoDestino;
    private final Set<Socket> vivos = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket servidor;
    private final int puerto;

    ProxyTcp(String hostDestino, int puertoDestino) {
        this.hostDestino = hostDestino;
        this.puertoDestino = puertoDestino;
        this.servidor = abrir(0);
        this.puerto = servidor.getLocalPort();
    }

    int puerto() {
        return puerto;
    }

    synchronized void caer() {
        cerrar(servidor);
        vivos.forEach(ProxyTcp::cerrar);
        vivos.clear();
    }

    synchronized void reponer() {
        if (servidor.isClosed()) {
            servidor = abrir(puerto);
        }
    }

    private ServerSocket abrir(int puertoLocal) {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", puertoLocal));
            Thread t = new Thread(() -> aceptar(s), "proxy-rabbit-prueba");
            t.setDaemon(true);
            t.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo abrir el proxy de prueba", e);
        }
    }

    private void aceptar(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket cliente = s.accept();
                Socket destino = new Socket(hostDestino, puertoDestino);
                vivos.add(cliente);
                vivos.add(destino);
                tuberia(cliente, destino);
                tuberia(destino, cliente);
            } catch (IOException e) {
                if (s.isClosed()) {
                    return;
                }
            }
        }
    }

    private void tuberia(Socket origen, Socket destino) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = origen.getInputStream(); OutputStream out = destino.getOutputStream()) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // conexión cortada: es lo esperado al caer el proxy
            } finally {
                cerrar(origen);
                cerrar(destino);
                vivos.remove(origen);
                vivos.remove(destino);
            }
        }, "tuberia-rabbit-prueba");
        t.setDaemon(true);
        t.start();
    }

    private static void cerrar(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // ya cerrado
        }
    }
}
