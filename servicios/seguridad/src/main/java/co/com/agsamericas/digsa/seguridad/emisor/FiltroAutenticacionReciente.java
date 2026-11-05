package co.com.agsamericas.digsa.seguridad.emisor;

import co.com.agsamericas.digsa.seguridad.identidad.ServicioIngreso;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B6 (revisión de la integradora, M1): la sesión del emisor autenticada solo sirve para la autorización que sigue al ingreso, en los
 * {@link #VIGENCIA} siguientes al código MFA. Si nadie navega a {@code /oauth2/authorize} a tiempo (pestaña cerrada, red caída), la sesión
 * se descarta y la autorización vuelve a {@code /ingreso}: en un equipo compartido, la persona siguiente no recibe un código a nombre de
 * la anterior. Va justo después de que el emisor carga el contexto de seguridad de la sesión.
 */
class FiltroAutenticacionReciente extends OncePerRequestFilter {

    static final Duration VIGENCIA = Duration.ofSeconds(60);

    private final Clock reloj;

    FiltroAutenticacionReciente(Clock reloj) {
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !"/oauth2/authorize".equals(req.getRequestURI().substring(req.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        HttpSession s = req.getSession(false);
        if (s != null && autenticada(s)) {
            Object t = s.getAttribute(ServicioIngreso.AUTENTICADO);
            boolean reciente = t instanceof Number ms && reloj.millis() - ms.longValue() <= VIGENCIA.toMillis();
            if (!reciente) {
                s.invalidate();
                SecurityContextHolder.clearContext();
                SecurityContextHolder.getContextHolderStrategy().setContext(SecurityContextHolder.createEmptyContext());
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean autenticada(HttpSession s) {
        return s.getAttribute("SPRING_SECURITY_CONTEXT") != null;
    }
}
