private ServerWebExchange buildExchange(String bodyJson, String serviceId, HttpMethod method) {
    MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest
        .method(method, "http://localhost/" + serviceId + "/endpoint")
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    if (bodyJson != null) {
        builder.body(bodyJson);
    }
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    exchange.getAttributes().put("serviceId", serviceId);
    return exchange;
}
