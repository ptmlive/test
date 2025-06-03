package com.example.gateway.filters;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.sleuth.enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
public class AddRequestIdHeaderFilter implements GlobalFilter {

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan == null) {
                log.debug("No current span; skipping x-request-id header");
                return;
            }

            String traceId = currentSpan.context().traceId();
            if (!StringUtils.hasText(traceId)) {
                log.debug("Trace ID is empty; skipping x-request-id header");
                return;
            }

            log.info("Adding x-request-id header with trace ID {}", traceId);
            exchange.getResponse().getHeaders().add("x-request-id", traceId);
        }));
    }
}
