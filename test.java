import com.example.gateway.config.AuthorizationConfiguration;
import com.example.gateway.security.AuthenticatedUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddUserAsBodyFieldFilter implements GlobalFilter, Ordered {

    private final AuthenticatedUser user;
    private final ObjectMapper objectMapper;
    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = user.getUserId();
        if (!StringUtils.hasText(userId)) {
            log.debug("No authenticated user; skipping body enrichment");
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.POST.equals(request.getMethod())) {
            log.debug("Not a POST request; skipping body enrichment");
            return chain.filter(exchange);
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            log.debug("No route found; skipping body enrichment");
            return chain.filter(exchange);
        }

        String serviceId = route.getUri().getHost();
        if (!authorizationConfiguration.getServicesToAddUserAsPostBodyField().contains(serviceId)) {
            log.debug("Service '{}' does not expect user in body; skipping", serviceId);
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    Map<String, Object> payload;
                    try {
                        String bodyString = new String(bytes, StandardCharsets.UTF_8);
                        payload = objectMapper.readValue(bodyString, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        payload = new HashMap<>();
                    }
                    payload.put("user", userId);

                    byte[] newBytes;
                    try {
                        newBytes = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        log.warn("Failed to serialize modified payload; forwarding original request", e);
                        return chain.filter(exchange);
                    }

                    Flux<DataBuffer> bodyFlux = Flux.just(exchange.getResponse()
                            .bufferFactory()
                            .wrap(newBytes));

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
                    log.debug("Empty request body; skipping enrichment for service '{}'", serviceId);
                    return chain.filter(exchange);
                }));
    }
}
