import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;

@Slf4j
@Component
@RequiredArgsConstructor
public class BasePathPrependFilter implements GlobalFilter, Ordered {

    private final ReactiveDiscoveryClient discoveryClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String originalPath = exchange.getRequest().getURI().getPath();

        return discoveryClient.getServices()
            .flatMapMany(discoveryClient::getInstances)
            .filter(inst -> {
                String serviceId = inst.getServiceId().toLowerCase();
                return originalPath.startsWith("/" + serviceId);
            })
            .next()
            .defaultIfEmpty(null)
            .flatMap(serviceInstance -> {
                if (serviceInstance == null) {
                    log.info("No matching service found for path: {}", originalPath);
                    return chain.filter(exchange);
                }

                String basePath = serviceInstance.getMetadata().getOrDefault("basePath", "");

                if (basePath.isEmpty()) {
                    log.info("Service '{}' is on-prem. Passing path through unchanged: {}", serviceInstance.getServiceId(), originalPath);
                    return chain.filter(exchange);
                }

                // Rewrite path
                String servicePrefix = "/" + serviceInstance.getServiceId().toLowerCase();
                String adjustedPath = originalPath.replaceFirst(servicePrefix, basePath);

                log.info("Rewriting path for service '{}': {} → {}", serviceInstance.getServiceId(), originalPath, adjustedPath);

                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .path(adjustedPath)
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            });
    }

    @Override
    public int getOrder() {
        return -1; // ensure it runs before routing
    }
}
