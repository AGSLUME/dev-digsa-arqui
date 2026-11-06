package co.com.agsamericas.digsa.visitas.compartido;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * La pantalla que exige cada ruta se revisa <b>antes</b> de leer el cuerpo (revisión de seguridad de la integradora, BAJA 8): sin la pantalla,
 * un JSON mal formado responde 403 y no 400, y quien no tiene permiso no hace trabajar al lector de JSON. Los {@code @PreAuthorize} de los
 * controladores siguen siendo la regla de cada operación; esto es una primera barrera con la misma tabla (monolito {@code @permisos.pantalla}).
 * El {@link AccessDeniedException} lo convierte en 403 RFC 7807 el {@code ManejadorErrores} de comun.
 */
@Component
public class AutorizacionPorRuta implements HandlerInterceptor, WebMvcConfigurer {

    /** Prefijo de la ruta → autoridad de pantalla. Se agregan aquí las pantallas nuevas (VA2: agenda, calendario, solicitudes…). */
    static final Map<String, String> PANTALLAS = Map.of(
            "/api/catalogos", "PANTALLA_CATALOGOS",
            "/api/maestros", "PANTALLA_MAESTROS");

    /** Rutas que solo exigen sesión (monolito: {@code isAuthenticated()}). */
    static final List<String> SOLO_SESION = List.of("/api/catalogos/municipios");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ruta = request.getRequestURI().substring(request.getContextPath().length());
        if (SOLO_SESION.contains(ruta)) {
            return true;
        }
        for (Map.Entry<String, String> e : PANTALLAS.entrySet()) {
            if (ruta.equals(e.getKey()) || ruta.startsWith(e.getKey() + "/")) {
                if (!tiene(e.getValue())) {
                    throw new AccessDeniedException("Sin la pantalla " + e.getValue());
                }
                return true;
            }
        }
        return true;
    }

    private static boolean tiene(String autoridad) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) {
            return false;
        }
        for (GrantedAuthority g : a.getAuthorities()) {
            if (autoridad.equals(g.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
