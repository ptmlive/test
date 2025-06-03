@Component
@RequiredArgsConstructor
public class BasePathPrependFilter implements GlobalFilter, Ordered {

    private final ReactiveDiscoveryClient discoveryClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String originalPath = exchange.getRequest().getURI().getPath();
        String host = exchange.getRequest().getHeaders().getFirst("Host"); // lub extract z route

        return discoveryClient.getServices()
            .flatMapMany(discoveryClient::getInstances)
            .filter(inst -> {
                URI uri = inst.getUri();
                return uri.getHost() != null && originalPath.startsWith("/" + inst.getServiceId().toLowerCase());
            })
            .next()
            .defaultIfEmpty(null)
            .flatMap(serviceInstance -> {
                if (serviceInstance == null) {
                    return chain.filter(exchange); // no match, proceed
                }

                String basePath = serviceInstance.getMetadata().getOrDefault("basePath", "");

                if (basePath.isEmpty()) {
                    return chain.filter(exchange); // on-prem, no rewrite
                }

                // GKE with basePath, rewrite
                String adjustedPath = originalPath.replaceFirst("/" + serviceInstance.getServiceId().toLowerCase(), basePath);
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .path(adjustedPath)
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            });
    }

    @Override
    public int getOrder() {
        return -1; // before Routing
    }
}
