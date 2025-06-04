package com.example.gateway.filters;

import com.example.gateway.config.AuthorizationConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

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
        // by default, only "my-service" expects user in POST body
        when(authConfig.getServicesToAddUserAsPostBodyField()).thenReturn(Collections.singleton("my-service"));
        filter = new AddUserAsBodyFieldFilter(objectMapper, authConfig);
    }

    private ServerWebExchange buildExchange(String bodyJson, String serviceId, HttpMethod method) {
        MockServerHttpRequest.Builder builder = MockServerHttpRequest
            .method(method, "http://localhost/" + serviceId + "/endpoint")
            .contentType(MediaType.APPLICATION_JSON);
        if (bodyJson != null) {
            builder.body(bodyJson);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        // set serviceId attribute for routing
        exchange.getAttributes().put("serviceId", serviceId);
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
        // given: a GET request to my-service
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.GET);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then: filter completes, body must remain unchanged (no "user" field)
        StepVerifier.create(result).verifyComplete();

        // attempt to read request body from the mutated exchange
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenServiceIsMissingInConfiguration() {
        // given: service not in config
        when(authConfig.getServicesToAddUserAsPostBodyField()).thenReturn(Collections.emptySet());
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "other-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then: no enrichment, body remains the original
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenAuthenticatedButEmployeeIdClaimNotPresent() {
        // given: POST to my-service, JWT missing “employeeId”
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("wrongClaim", "value");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then: no enrichment
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldNotApplyFilterWhenNotAuthenticated() {
        // given: POST to my-service, no authentication context
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty());

        // then: no enrichment
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void shouldAddUserAsBodyField() {
        // given: POST to my-service with valid JWT having "employeeId" claim
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, e -> Mono.empty()).contextWrite(ctx);

        // then: original JSON plus "user":"user123"
        StepVerifier.create(result).verifyComplete();
        String body = readRequestBody(exchange);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("user").asText()).isEqualTo("user123");
        assertThat(json.get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void shouldBeExecutedBeforeSendingTheRequest() {
        // given: ensure chain is invoked
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

    // Utility method to read request body from the (possibly mutated) exchange
    private String readRequestBody(ServerWebExchange exchange) {
        Flux<DataBuffer> bodyFlux = exchange.getRequest().getBody();
        return bodyFlux
            .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .reduce("", String::concat)
            .block();
    }
}
