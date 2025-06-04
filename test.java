@Test
void shouldSkipWhenNoEmployeeIdClaim() {
    // given
    ServerWebExchange exchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);

    // 1) zakładamy, że wklejamy kontekst, ALE o złym kluczu (wrongClaim)
    Context ctx = authContextWithJwtClaim("wrongClaim", "value");

    // when
    Mono<Void> result = filter.filter(exchange, e -> Mono.empty())
                              .contextWrite(ctx);

    // then – filtr wypisze „no authenticated user…” i NIE wzbogaci body
    StepVerifier.create(result).verifyComplete();
    String body = readRequestBody(exchange);
    assertThat(body).isEqualTo("{\"foo\":\"bar\"}");
}

@Test
void shouldAddUserFieldWhenEmployeeIdPresent() {
    // given
    ServerWebExchange originalExchange = buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);

    // 1) Tworzymy mock Jwt tak, aby getClaimAsString("employeeId") zwracał "user123"
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaimAsString("employeeId")).thenReturn("user123");
    TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null);
    Context ctx = ReactiveSecurityContextHolder.withAuthentication(auth);

    // 2) Przygotowujemy „capturingChain”, żeby złapać, jak filtr zmodyfikuje exchange
    final ServerWebExchange[] captured = new ServerWebExchange[1];
    GatewayFilterChain capturingChain = e -> {
        captured[0] = e;
        return Mono.empty();
    };

    // when
    Mono<Void> result = filter.filter(originalExchange, capturingChain)
                              .contextWrite(ctx);

    // then – po zakończeniu filtr wrzuci exchange z nowym ciałem
    StepVerifier.create(result).verifyComplete();

    // 3) Odczytujemy ciało z „captured[0]”
    ServerWebExchange mutated = captured[0];
    String body = readRequestBody(mutated);
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("user").asText()).isEqualTo("user123");
    assertThat(json.get("foo").asText()).isEqualTo("bar");
}
