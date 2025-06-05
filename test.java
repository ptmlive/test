import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class CorsFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();

        log.debug("CORS headers before changes: {}", headers);

        String requestOrigin = exchange.getRequest().getHeaders().getOrigin();
        if (requestOrigin != null) {
            headers.set("Access-Control-Allow-Origin", requestOrigin);
        }

        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization, X-Requested-With");
        headers.set("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT, PATCH");
        headers.set("Access-Control-Expose-Headers", "*");
        headers.set("Access-Control-Max-Age", "3600");

        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }

        log.debug("CORS headers after changes: {}", headers);

        return chain.filter(exchange.mutate().response(exchange.getResponse()).build());
    }
}
