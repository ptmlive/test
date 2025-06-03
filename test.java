package com.example.gateway.filters;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.TraceContext;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.Ordered;
import org.springframework.context.annotation.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.sleuth.enabled", havingValue = "true", matchIfMissing = true)
public class AddRequestIdHeaderFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan == null) {
                log.debug("No current span available; skipping x-request-id header");
                return;
            }
            TraceContext context = currentSpan.context();
            String traceId = context != null ? context.traceIdString() : null;
            if (traceId == null) {
                log.debug("Trace ID is null; skipping x-request-id header");
                return;
            }
            log.info("Adding x-request-id header with trace ID {}", traceId);
            exchange.getResponse().getHeaders().add("x-request-id", traceId);
        }));
    }
}
