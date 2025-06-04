package com.example.gateway.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AddUserAsBodyFieldFilterTest {

    private AddUserAsBodyFieldFilter filter;
    private AuthorizationConfiguration authConfig;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        authConfig = mock(AuthorizationConfiguration.class);
        objectMapper = new ObjectMapper();
        when(authConfig.getServicesToAddUserAsPostBodyField())
            .thenReturn(Collections.singleton("my-service"));
        filter = new AddUserAsBodyFieldFilter(objectMapper, authConfig);
    }

    private ServerWebExchange buildExchange(String bodyJson, String serviceId, HttpMethod method) {
        MockServerHttpRequest.Builder builder =
            MockServerHttpRequest
                .method(method, "http://localhost/" + serviceId + "/endpoint")
                .contentType(MediaType.APPLICATION_JSON);
        if (bodyJson != null) {
            builder.body(bodyJson);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        Route fakeRoute = Route
            .async()
            .id(serviceId)
            .uri("http://" + serviceId)
            .build();
        exchange.getAttributes().put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            fakeRoute
        );
        return exchange;
    }

    private Context authContextWithJwtClaim(String claimKey, String claimValue) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim(claimKey, claimValue)
            .build();
        TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null);
        return ReactiveSecurityContextHolder.withAuthentication(auth);
    }

    @Test
    void shouldApplyFilterOnlyWhenRequestMethodIsPost() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.GET);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenServiceIsMissingInConfiguration() {
        // given
        when(authConfig.getServicesToAddUserAsPostBodyField()).thenReturn(Collections.emptySet());
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "other-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenAuthenticatedButEmployeeIdClaimNotPresent() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("wrongClaim", "value");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenNotAuthenticated() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty());

        // then
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldAddUserAsBodyField() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("user").asText()).isEqualTo("user123");
        assertThat(json.get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void shouldBeExecutedBeforeSendingTheRequest() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");
        boolean[] chainInvoked = {false};

        GatewayFilterChain customChain = e -> {
            chainInvoked[0] = true;
            return Mono.empty();
        };

        // when
        Mono<Void> result = filter.filter(exchange, customChain).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        assertThat(chainInvoked[0]).isTrue();
    }

    private String readRequestBody(ServerWebExchange exchange) {
        return exchange.getRequest()
            .getBody()
            .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .reduce("", String::concat)
            .block();
    }
}
