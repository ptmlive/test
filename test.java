package com.example.gateway.filters;

import com.example.gateway.config.AuthorizationConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddUserAsBodyFieldFilter implements GlobalFilter {

    private final ObjectMapper objectMapper;
    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (!HttpMethod.POST.equals(request.getMethod())) {
            log.debug("skipping enrichment: not a POST request");
            return chain.filter(exchange);
        }

        String serviceId = extractServiceId(exchange);
        if (serviceId == null) {
            log.debug("skipping enrichment: no route found");
            return chain.filter(exchange);
        }

        if (isServiceNotConfigured(serviceId)) {
            log.debug("skipping enrichment: service '{}' not configured", serviceId);
            return chain.filter(exchange);
        }

        return extractUserId()
            .flatMap(userId -> {
                if (!StringUtils.hasText(userId)) {
                    log.debug("skipping enrichment: empty employeeId claim");
                    return chain.filter(exchange);
                }
                return enrichBody(exchange, chain, request, serviceId, userId);
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("skipping enrichment: no authenticated user or missing employeeId");
                return chain.filter(exchange);
            }));
    }

    private String extractServiceId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return (route != null) ? route.getUri().getHost() : null;
    }

    private boolean isServiceNotConfigured(String serviceId) {
        return !authorizationConfiguration.getServicesToAddUserAsPostBodyField().contains(serviceId);
    }

    private Mono<String> extractUserId() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof Jwt)
            .cast(Jwt.class)
            .map(jwt -> jwt.getClaimAsString("employeeId"))
            .onErrorResume(e -> {
                log.warn("cannot extract employeeId from JWT; skipping enrichment", e);
                return Mono.empty();
            });
    }

    private Mono<Void> enrichBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                  ServerHttpRequest request, String serviceId, String userId) {
        return DataBufferUtils.join(request.getBody())
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);

                Map<String, Object> payload = parseOriginalBody(bytes);
                payload.put("user", userId);

                byte[] newBytes;
                try {
                    newBytes = objectMapper.writeValueAsBytes(payload);
                } catch (Exception ex) {
                    log.warn("failed to serialize modified payload; forwarding original request", ex);
                    return forwardOriginalBody(exchange, chain, request, bytes);
                }

                ServerHttpRequest mutatedReq = buildMutatedRequest(exchange, request, newBytes);
                log.info("enriched request body with user='{}' for service '{}'", userId, serviceId);
                return chain.filter(exchange.mutate().request(mutatedReq).build());
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("skipping enrichment: empty request body");
                return chain.filter(exchange);
            }));
    }

    private Map<String, Object> parseOriginalBody(byte[] bytes) {
        try {
            String bodyString = new String(bytes, StandardCharsets.UTF_8);
            return objectMapper.readValue(bodyString, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private Mono<Void> forwardOriginalBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                           ServerHttpRequest request, byte[] bytes) {
        Flux<DataBuffer> originalBodyFlux = Flux.just(
            exchange.getResponse().bufferFactory().wrap(bytes)
        );
        ServerHttpRequest restoreReq = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return originalBodyFlux;
            }
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(bytes.length);
                return headers;
            }
        };
        return chain.filter(exchange.mutate().request(restoreReq).build());
    }

    private ServerHttpRequest buildMutatedRequest(ServerWebExchange exchange, ServerHttpRequest request, byte[] newBytes) {
        Flux<DataBuffer> newBodyFlux = Flux.just(
            exchange.getResponse().bufferFactory().wrap(newBytes)
        );
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return newBodyFlux;
            }
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(newBytes.length);
                return headers;
            }
        };
    }
}
