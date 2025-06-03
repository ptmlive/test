package com.example.gateway.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.Ordered;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

@Slf4j
@Component
public class BasePathRewriteFilter implements GlobalFilter, Ordered {

    private final ReactiveDiscoveryClient discoveryClient;

    public BasePathRewriteFilter(ReactiveDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public int getOrder() {
        return 10160;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        URI lbUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        if (lbUri == null) {
            log.debug("No LB URI found; skipping basePath rewrite");
            return chain.filter(exchange);
        }

        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            log.debug("No route attribute found; skipping basePath rewrite");
            return chain.filter(exchange);
        }

        String serviceId = route.getUri().getHost();
        String instanceHost = lbUri.getHost();
        int instancePort = lbUri.getPort();

        return discoveryClient.getInstances(serviceId)
                .filter(inst -> inst.getHost().equals(instanceHost) && inst.getPort() == instancePort)
                .next()
                .flatMap(inst -> {
                    String basePath = inst.getMetadata().get("basepath");
                    if (!StringUtils.hasText(basePath)) {
                        log.debug("Instance {}:{} has no basepath metadata; skipping rewrite", instanceHost, instancePort);
                        return chain.filter(exchange);
                    }

                    String originalPath = lbUri.getPath();
                    String newPath = "/" + basePath + originalPath;
                    URI rewritten = UriComponentsBuilder.fromUri(lbUri)
                            .replacePath(newPath)
                            .build(true)
                            .toUri();

                    log.debug("Rewriting path from '{}' to '{}' for service '{}'", originalPath, newPath, serviceId);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, rewritten);
                    return chain.filter(exchange);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("No matching instance found for {}:{}; skipping basePath rewrite", instanceHost, instancePort);
                    return chain.filter(exchange);
                }));
    }
}
