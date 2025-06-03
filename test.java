package com.example.gateway.filters;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AddRequestIdHeaderFilterTest {

    private Tracer tracer;
    private AddRequestIdHeaderFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        filter = new AddRequestIdHeaderFilter(tracer);
        chain = exchange -> Mono.empty();
    }

    private ServerWebExchange buildExchange() {
        MockServerHttpRequest request = MockServerHttpRequest.post("http://localhost/test")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
            .build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        return new DefaultServerWebExchange(request, response, null);
    }

    @Test
    void shouldNotAddHeaderWhenNoCurrentSpan() {
        // given
        when(tracer.currentSpan()).thenReturn(null);
        ServerWebExchange exchange = buildExchange();

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.containsKey("x-request-id")).isFalse();
    }

    @Test
    void shouldNotAddHeaderWhenTraceIdIsEmpty() {
        // given
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("");
        ServerWebExchange exchange = buildExchange();

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.containsKey("x-request-id")).isFalse();
    }

    @Test
    void shouldAddHeaderWhenTraceIdPresent() {
        // given
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("abc123");
        ServerWebExchange exchange = buildExchange();

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result).verifyComplete();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("x-request-id")).isEqualTo("abc123");
    }

    @Test
    void shouldPropagateErrorButStillAddHeaderWhenChainErrors() {
        // given
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("xyz");
        GatewayFilterChain errorChain = exchange -> Mono.error(new RuntimeException("downstream failure"));
        ServerWebExchange exchange = buildExchange();

        // when
        Mono<Void> result = filter.filter(exchange, errorChain);

        // then
        StepVerifier.create(result)
            .expectErrorMessage("downstream failure")
            .verify();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("x-request-id")).isEqualTo("xyz");
    }
}
