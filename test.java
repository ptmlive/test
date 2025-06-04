@Test
void shouldPropagateErrorWithoutAddingHeaderWhenChainErrors() {
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
    assertThat(headers.containsKey("x-request-id")).isFalse();
}
