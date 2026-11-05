package co.com.agsamericas.digsa.gateway;

import java.net.HttpCookie;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Filtro {@code SoloCookieEmisor} (ADR-014) para las rutas del emisor ({@code /api/auth/**}, {@code /oauth2/**}, ...): hacia
 * {@code seguridad} solo viaja la cookie de su sesión ({@code LUME_IDP}), nunca la del gateway ({@code LUME_SESION}) ni la de CSRF; y de
 * vuelta solo se acepta el {@code Set-Cookie} de {@code LUME_IDP}, así un servicio no puede pisar las cookies del BFF.
 */
@Component
class SoloCookieEmisorGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    static final String COOKIE = "LUME_IDP";

    SoloCookieEmisorGatewayFilterFactory() {
        super(Object.class);
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            ServerHttpRequest req = exchange.getRequest();
            String propia = req.getCookies().getOrDefault(COOKIE, List.of()).stream()
                    .map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
            ServerHttpRequest filtrada = req.mutate().headers(h -> {
                h.remove(HttpHeaders.COOKIE);
                if (!propia.isEmpty()) {
                    h.set(HttpHeaders.COOKIE, propia);
                }
            }).build();
            exchange.getResponse().beforeCommit(() -> {
                HttpHeaders h = exchange.getResponse().getHeaders();
                List<String> setCookie = h.get(HttpHeaders.SET_COOKIE);
                if (setCookie != null) {
                    List<String> solo = setCookie.stream().filter(SoloCookieEmisorGatewayFilterFactory::esDelEmisor).toList();
                    h.remove(HttpHeaders.SET_COOKIE);
                    solo.forEach(c -> h.add(HttpHeaders.SET_COOKIE, c));
                }
                return reactor.core.publisher.Mono.empty();
            });
            return chain.filter(exchange.mutate().request(filtrada).build());
        };
    }

    static boolean esDelEmisor(String setCookie) {
        try {
            return HttpCookie.parse(setCookie).stream().allMatch(c -> COOKIE.equals(c.getName()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
