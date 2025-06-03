@Component
@Order(-1)
@Slf4j
public class AuthenticationFilter implements GlobalFilter {

    @Value("${scp.http.security.authenticated:}")
    private List<String> authenticatedEndpoints;

    private final ObjectProvider<ScpAuthServerClient> scpAuthServerClientProvider;

    public AuthenticationFilter(ObjectProvider<ScpAuthServerClient> scpAuthServerClientProvider) {
        this.scpAuthServerClientProvider = scpAuthServerClientProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        boolean isSecured = authenticatedEndpoints.stream().anyMatch(path::startsWith);

        if (!isSecured) {
            return chain.filter(exchange);
        }

        List<String> authHeaders = exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);

        if (authHeaders.isEmpty() || !authHeaders.get(0).startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeaders.get(0).substring(7);

        try {
            CheckedToken checkedToken = scpAuthServerClientProvider.getObject()
                .checkToken(new UserToken(token));

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-id", checkedToken.getEmployeeId())
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception ex) {
            log.warn("Token validation failed", ex);
            return unauthorized(exchange, "Invalid token");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
