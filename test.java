

@Slf4j
@Component
@RequiredArgsConstructor
@Order(101)
public class OverrideAuthorizationHeaderFilter implements GlobalFilter {

    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.debug("OverrideAuthorizationHeaderFilter: start processing");
        Route route = getRoute(exchange);
        if (route == null) {
            log.debug("OverrideAuthorizationHeaderFilter: no route found – skipping");
            return chain.filter(exchange);
        }

        String serviceId = route.getUri().getHost();
        if (!requiresBasicAuth(serviceId)) {
            log.debug("OverrideAuthorizationHeaderFilter: basic auth not required for '{}' – skipping", serviceId);
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(Authentication::isAuthenticated)
            .flatMap(auth -> handleAuthenticated(exchange, chain, serviceId, auth))
            .switchIfEmpty(logAndContinue(exchange, "OverrideAuthorizationHeaderFilter: user not authenticated – skipping"));
    }

    private Route getRoute(ServerWebExchange exchange) {
        return exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    }

    private boolean requiresBasicAuth(String serviceId) {
        return authorizationConfiguration.isBasicAuthForService(serviceId);
    }

    private Mono<Void> handleAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain,
                                           String serviceId, Authentication auth) {
        Object principal = auth.getPrincipal();
        if (!(principal instanceof Jwt)) {
            log.debug("OverrideAuthorizationHeaderFilter: principal is not Jwt – skipping");
            return chain.filter(exchange);
        }

        Jwt jwt = (Jwt) principal;
        BasicAuthService basicAuthService = authorizationConfiguration.getBasicAuthServices().get(serviceId);
        if (basicAuthService == null) {
            log.debug("OverrideAuthorizationHeaderFilter: no BasicAuthService for '{}' – skipping", serviceId);
            return chain.filter(exchange);
        }

        Set<String> requiredAuthorities = getRequiredAuthorities(basicAuthService, exchange);
        List<?> userAuthorities = jwt.getClaimAsStringList("authorities");
        if (!hasAuthority(requiredAuthorities, userAuthorities)) {
            log.debug("OverrideAuthorizationHeaderFilter: insufficient authorities for '{}' – returning 403", serviceId);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return addBasicHeaderAndContinue(exchange, chain, serviceId, basicAuthService);
    }

    private Set<String> getRequiredAuthorities(BasicAuthService basicAuthService, ServerWebExchange exchange) {
        return basicAuthService.getRequiredAuthorities(exchange.getRequest());
    }

    private boolean hasAuthority(Set<String> required, List<?> userAuthorities) {
        if (required.isEmpty()) {
            return true;
        }
        return userAuthorities.stream()
            .map(Object::toString)
            .anyMatch(required::contains);
    }

    private Mono<Void> addBasicHeaderAndContinue(ServerWebExchange exchange, GatewayFilterChain chain,
                                                 String serviceId, BasicAuthService basicAuthService) {
        String headerValue = basicAuthService.getBasicAuthorizationHeader();
        log.info("OverrideAuthorizationHeaderFilter: added Basic Authorization for '{}'", serviceId);

        ServerWebExchange mutated = exchange.mutate()
            .request(exchange.getRequest().mutate()
                .header(HttpHeaders.AUTHORIZATION, headerValue)
                .build())
            .build();

        return chain.filter(mutated);
    }

    private Mono<Void> logAndContinue(ServerWebExchange exchange, String message) {
        log.debug(message);
        return exchange.getResponse().setComplete().then(Mono.defer(() -> exchange.getResponse().setComplete()));
    }
}
