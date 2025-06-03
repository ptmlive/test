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
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Wersja filtra, która w reaktywny sposób pobiera claim "employeeId" z JWT
 * korzystając z ReactiveSecurityContextHolder, zamiast wstrzykiwać bean @RequestScope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddUserAsBodyFieldFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;
    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1) Tylko POST
        if (!HttpMethod.POST.equals(request.getMethod())) {
            log.debug("Not a POST request; skipping body enrichment");
            return chain.filter(exchange);
        }

        // 2) Znajdź serviceId z routingu
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            log.debug("No route found; skipping body enrichment");
            return chain.filter(exchange);
        }
        String serviceId = route.getUri().getHost();

        // 3) Jeśli ten serviceId nie oczekuje pola "user" w body, pomiń
        if (!authorizationConfiguration.getServicesToAddUserAsPostBodyField().contains(serviceId)) {
            log.debug("Service '{}' does not expect user in body; skipping", serviceId);
            return chain.filter(exchange);
        }

        // 4) Pobierz claim "employeeId" z JWT w reaktywny sposób
        Mono<String> userIdMono = ReactiveSecurityContextHolder
                .getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof Authentication)
                .map(Authentication::getPrincipal)
                .filter(principal -> principal instanceof Jwt)
                .cast(Jwt.class)
                .map(jwt -> jwt.getClaimAsString("employeeId"))
                .onErrorResume(e -> {
                    log.warn("Cannot extract employeeId from JWT; skipping enrichment", e);
                    return Mono.empty();
                });

        // 5) Jeśli brak userId (np. nie ma tokena lub claimu), pomiń
        return userIdMono
                .flatMap(userId -> {
                    if (!StringUtils.hasText(userId)) {
                        log.debug("Empty employeeId claim; skipping enrichment");
                        return chain.filter(exchange);
                    }
                    // 6) Po pobraniu userId modyfikujemy ciało
                    return DataBufferUtils.join(request.getBody())
                            .flatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);

                                // 7) Odczytaj pierwotne body JSON do Map
                                Map<String, Object> payload;
                                try {
                                    String bodyString = new String(bytes, StandardCharsets.UTF_8);
                                    payload = objectMapper.readValue(bodyString, new TypeReference<Map<String, Object>>() {});
                                } catch (Exception e) {
                                    payload = new HashMap<>();
                                }
                                // 8) Dodajemy pole "user"
                                payload.put("user", userId);

                                // 9) Serializacja zmodyfikowanego payload do bajtów
                                byte[] newBytes;
                                try {
                                    newBytes = objectMapper.writeValueAsBytes(payload);
                                } catch (Exception e) {
                                    log.warn("Failed to serialize modified payload; forwarding original request", e);
                                    // W razie błędu – przekaż oryginalne żądanie bez zmian
                                    return chain.filter(exchange);
                                }

                                Flux<DataBuffer> bodyFlux = Flux.just(
                                        exchange.getResponse()
                                                .bufferFactory()
                                                .wrap(newBytes)
                                );

                                // 10) Budujemy nowe ServerHttpRequestDecorator
                                ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return bodyFlux;
                                    }

                                    @Override
                                    public HttpHeaders getHeaders() {
                                        HttpHeaders headers = new HttpHeaders();
                                        headers.putAll(super.getHeaders());
                                        headers.setContentLength(newBytes.length);
                                        return headers;
                                    }
                                };

                                log.info("Enriched request body with 'user' field for service '{}'", serviceId);
                                return chain.filter(exchange.mutate().request(mutatedRequest).build());
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // Gdy body było puste (Flux pusty)
                                log.debug("Empty request body; skipping enrichment for service '{}'", serviceId);
                                return chain.filter(exchange);
                            }));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Nie udało się pobrać userId → pomiń
                    log.debug("No authenticated user or no employeeId claim; skipping enrichment");
                    return chain.filter(exchange);
                }));
    }
}
