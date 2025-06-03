import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@Order(102)
public class AddServiceIdHeaderFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Object serviceIdObj = exchange.getAttribute("serviceId");
            if (serviceIdObj != null) {
                String serviceId = serviceIdObj.toString();
                log.info("Adding x-service-id header with value {}", serviceId);
                exchange.getResponse().getHeaders().add("x-service-id", serviceId);
            } else {
                log.debug("No serviceId attribute; skipping x-service-id header");
            }
        }));
    }
}
