package com.example.gateway.filters;

import com.example.gateway.config.AuthorizationConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.Ordered;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
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
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddUserHeaderAndParamFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;
    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            log.debug("AddUserHeaderAndParamFilter: request is not POST – skipping");
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth instanceof Authentication)
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof Jwt)
            .cast(Jwt.class)
            .map(jwt -> jwt.getClaimAsString("employeeId"))
            .flatMap(userId -> {
                if (!StringUtils.hasText(userId)) {
                    log.debug("AddUserHeaderAndParamFilter: employeeId claim is empty – skipping");
                    return chain.filter(exchange);
                }

                Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                if (route == null) {
                    log.debug("AddUserHeaderAndParamFilter: no route found – skipping");
                    return chain.filter(exchange);
                }
                String serviceId = route.getUri().getHost();

                ServerHttpRequest baseRequest = exchange.getRequest();
                ServerHttpRequest requestWithHeaders = baseRequest.mutate()
                    .header("userId", userId)
                    .header("user", userId)
                    .build();

                if (authorizationConfiguration.getServicesToAddUserAsQueryParams().contains(serviceId)) {
                    URI originalUri = requestWithHeaders.getURI();
                    MultiValueMap<String, String> params = requestWithHeaders.getQueryParams();
                    URI updatedUri;
                    try {
                        updatedUri = org.springframework.web.util.UriComponentsBuilder
                            .fromUri(originalUri)
                            .replaceQueryParams(params)
                            .queryParam("user", userId)
                            .build(true)
                            .toUri();
                    } catch (Exception e) {
                        log.warn("AddUserHeaderAndParamFilter: failed to build URI with user param – forwarding without query param", e);
                        log.debug("AddUserHeaderAndParamFilter: forwarding requestWithHeaders without query param for service '{}', userId={}", serviceId, userId);
                        return chain.filter(exchange.mutate().request(requestWithHeaders).build());
                    }

                    ServerHttpRequest requestWithHeadersAndParam = requestWithHeaders.mutate()
                        .uri(updatedUri)
                        .build();

                    log.info("AddUserHeaderAndParamFilter: added headers and query param for service '{}', userId={}", serviceId, userId);
                    log.debug("AddUserHeaderAndParamFilter: forwarding requestWithHeadersAndParam for service '{}', userId={}", serviceId, userId);
                    return chain.filter(exchange.mutate().request(requestWithHeadersAndParam).build());
                }

                Flux<DataBuffer> cachedBody = DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMapMany(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        Map<String, Object> payload;
                        try {
                            payload = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                                new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            payload = new HashMap<>();
                        }
                        payload.put("user", userId);
                        byte[] newBytes;
                        try {
                            newBytes = objectMapper.writeValueAsBytes(payload);
                        } catch (Exception e) {
                            log.warn("AddUserHeaderAndParamFilter: failed to serialize payload – forwarding original", e);
                            log.debug("AddUserHeaderAndParamFilter: forwarding original body for service '{}', userId={}", serviceId, userId);
                            return Flux.from(exchange.getRequest().getBody());
                        }
                        return Flux.just(exchange.getResponse().bufferFactory().wrap(newBytes));
                    });

                ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(requestWithHeaders) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return cachedBody;
                    }

                    @Override
                    public HttpHeaders getHeaders() {
                        HttpHeaders headers = new HttpHeaders();
                        headers.putAll(super.getHeaders());
                        DataBuffer firstBuffer = cachedBody.blockFirst();
                        int length = (firstBuffer != null) ? firstBuffer.readableByteCount() : 0;
                        headers.setContentLength(length);
                        return headers;
                    }
                };

                log.info("AddUserHeaderAndParamFilter: added headers and enriched body for service '{}', userId={}", serviceId, userId);
                log.debug("AddUserHeaderAndParamFilter: forwarding mutatedRequest for service '{}', userId={}", serviceId, userId);
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("AddUserHeaderAndParamFilter: no authenticated user or no employeeId claim – skipping");
                return chain.filter(exchange);
            }));
    }
}
