package com.example.gateway.filters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class AddServiceIdHeaderFilterTest {

    private AddServiceIdHeaderFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new AddServiceIdHeaderFilter();
        chain = exchange -> Mono.empty();
    }

    private ServerWebExchange buildExchange() {
        MockServerHttpRequest request = MockServerHttpRequest.get("http://localhost/test").build();
        return MockServerWebExchange.from(request);
    }

    @Test
    void shouldApplyFilterWhenServiceIdPresentInRequestContext() {
        // given
        ServerWebExchange exchange = buildExchange();
        exchange.getAttributes().put("serviceId", "my-service");

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("x-service-id")).isEqualTo("my-service");
    }

    @Test
    void shouldNotApplyFilterWhenServiceIdNotPresentInRequestContext() {
        // given
        ServerWebExchange exchange = buildExchange();
        // no "serviceId" attribute

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.containsKey("x-service-id")).isFalse();
    }

    @Test
    void shouldConvertNonStringServiceIdToStringAndAddHeader() {
        // given
        ServerWebExchange exchange = buildExchange();
        exchange.getAttributes().put("serviceId", 12345);

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("x-service-id")).isEqualTo("12345");
    }
}
