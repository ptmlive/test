import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
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
        when(authConfig.getServicesToAddUserAsPostBodyField()).thenReturn(Collections.singleton("my-service"));
        filter = new AddUserAsBodyFieldFilter(objectMapper, authConfig);
    }

    private ServerWebExchange buildExchange(String bodyJson, String serviceId) {
        MockServerHttpRequest.Builder builder = MockServerHttpRequest
            .post("http://localhost/" + serviceId + "/endpoint")
            .contentType(MediaType.APPLICATION_JSON)
            .body(bodyJson != null ? bodyJson : "");
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
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
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service");
        // change method to GET
        exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("http://localhost/my-service/endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"foo\":\"bar\"}")
        );
        exchange.getAttributes().put("serviceId", "my-service");
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, (e) -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = exchange.getRequest().getBody().collectList()
            .map(list -> {
                try {
                    return objectMapper.readTree(new String(list.get(0).asByteBuffer().array(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    return null;
                }
            })
            .block();
        assertThat(body).extractingJsonPathValue("user").isNull();
    }

    @Test
    void shouldNotApplyFilterWhenServiceIsMissingInConfiguration() {
        // given
        when(authConfig.getServicesToAddUserAsPostBodyField()).thenReturn(Collections.emptySet());
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "other-service");
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, (e) -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = exchange.getRequest().getBody().collectList()
            .map(list -> {
                try {
                    return objectMapper.readTree(new String(list.get(0).asByteBuffer().array(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    return null;
                }
            })
            .block();
        assertThat(body).extractingJsonPathValue("user").isNull();
    }

    @Test
    void shouldNotApplyFilterWhenAuthenticatedButEmployeeIdClaimNotPresent() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service");
        Context ctx = authContextWithJwtClaim("wrongClaim", "value");

        // when
        Mono<Void> result = filter.filter(exchange, (e) -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = exchange.getRequest().getBody().collectList()
            .map(list -> {
                try {
                    return objectMapper.readTree(new String(list.get(0).asByteBuffer().array(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    return null;
                }
            })
            .block();
        assertThat(body).extractingJsonPathValue("user").isNull();
    }

    @Test
    void shouldNotApplyFilterWhenNotAuthenticated() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service");

        // when
        Mono<Void> result = filter.filter(exchange, (e) -> Mono.empty());

        // then
        StepVerifier.create(result).verifyComplete();
        String body = exchange.getRequest().getBody().collectList()
            .map(list -> {
                try {
                    return objectMapper.readTree(new String(list.get(0).asByteBuffer().array(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    return null;
                }
            })
            .block();
        assertThat(body).extractingJsonPathValue("user").isNull();
    }

    @Test
    void shouldAddUserAsBodyField() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service");
        Context ctx = authContextWithJwtClaim("employeeId", "user123");

        // when
        Mono<Void> result = filter.filter(exchange, (e) -> Mono.empty()).contextWrite(ctx);

        // then
        StepVerifier.create(result).verifyComplete();
        String body = exchange.getRequest().getBody().collectList()
            .map(list -> {
                try {
                    return objectMapper.readTree(new String(list.get(0).asByteBuffer().array(), StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    return null;
                }
            })
            .block();
        assertThat(body).extractingJsonPathStringValue("user").isEqualTo("user123");
    }

    @Test
    void shouldBeExecutedBeforeSendingTheRequest() {
        // given
        ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service");
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
}
