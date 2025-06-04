@Test
void shouldAddUserFieldWhenEmployeeIdPresent() {
    // given
    ServerWebExchange originalExchange =
        buildExchange("{\"foo\":\"bar\"}", "my-service", HttpMethod.POST);

    // 1) Tworzymy mockowanego Jwt-a, który zwróci "user123" dla getClaimAsString("employeeId")
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaimAsString("employeeId")).thenReturn("user123");

    // 2) Umieszczamy ten Jwt w TestingAuthenticationToken, a następnie
    //    w Reactor-owym Context pod kluczem ReactiveSecurityContextHolder.KEY
    TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null);
    Context ctx = ReactiveSecurityContextHolder.withAuthentication(auth);

    // 3) Przygotowujemy „capturingChain”, żeby złapać zmieniony exchange
    ServerWebExchange[] captured = new ServerWebExchange[1];
    GatewayFilterChain capturingChain = e -> {
        captured[0] = e;
        return Mono.empty();
    };

    // when
    Mono<Void> result =
        filter.filter(originalExchange, capturingChain)
              .contextWrite(ctx);  // <-- to jest absolutnie kluczowe

    // then
    StepVerifier.create(result).verifyComplete();

    // Teraz filtr musiał wzbogacić „captured[0]” o pole user
    ServerWebExchange mutated = captured[0];
    String body = readRequestBody(mutated);
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("user").asText()).isEqualTo("user123");
    assertThat(json.get("foo").asText()).isEqualTo("bar");
}
