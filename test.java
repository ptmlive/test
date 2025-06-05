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
public class CorsGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();

        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin != null) {
            headers.set("Access-Control-Allow-Origin", origin);
            log.debug("CORS: Setting Access-Control-Allow-Origin to {}", origin);
        } else {
            headers.set("Access-Control-Allow-Origin", "*");
            log.debug("CORS: Setting Access-Control-Allow-Origin to *");
        }

        headers.set("Access-Control-Allow-Credentials", "true");
        log.debug("CORS: Setting Access-Control-Allow-Credentials to true");

        headers.set("Access-Control-Allow-Headers",
            "Origin,Content-Type,Accept,Authorization,X-Requested-With");
        log.debug("CORS: Setting Access-Control-Allow-Headers to Origin,Content-Type,Accept,Authorization,X-Requested-With");

        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        log.debug("CORS: Setting Access-Control-Allow-Methods to GET,POST,PUT,PATCH,DELETE,OPTIONS");

        headers.set("Access-Control-Expose-Headers", "*");
        log.debug("CORS: Setting Access-Control-Expose-Headers to *");

        headers.set("Access-Control-Max-Age", "3600");
        log.debug("CORS: Setting Access-Control-Max-Age to 3600");

        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            log.debug("CORS: Preflight request detected, returning 200 OK");
            return Mono.empty();
        }

        log.debug("CORS: Continuing filter chain for method {}", exchange.getRequest().getMethod());
        return chain.filter(exchange);
    }
}
