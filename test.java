@Component
@Order(10160) // ustawiamy wyższy order niż domyślny LoadBalancerClientFilter (który jest ok. 10150)
public class BasePathRewriteFilter implements GlobalFilter, Ordered {

    private final ReactiveDiscoveryClient discoveryClient;

    public BasePathRewriteFilter(ReactiveDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public int getOrder() {
        // LoadBalancerClientFilter ma order = 10150, więc nasz musi być większy, np. 10160
        return 10160;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        // 1) Odejmujemy atrybut, który LoadBalancerClientFilter właśnie ustawił na wymuszenie konkretnego host:port
        URI lbUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

        if (lbUri == null) {
            // Jeżeli nie ma jeszcze URI z LoadBalancer (być może nie jest to trasa z lb://...), przejdź dalej
            return chain.filter(exchange);
        }

        // 2) Odczytujemy 'route', aby z niej wyciągnąć serviceId (bo URI może mieć formę lb://SERVICE-ID)
        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        // Zakładamy, że ruta była skonfigurowana jako lb://MY-SERVICE, więc host z route.getUri() to serviceId
        String serviceId = route.getUri().getHost();

        // 3) Z dostępnego już URI (po LB) pobieramy host i port instancji, którą LoadBalancer wybrał
        String instanceHost = lbUri.getHost();
        int instancePort = lbUri.getPort();

        // 4) Znajdujemy tę instancję w Eurece (ReactiveDiscoveryClient) po nazwie usługi, filtrując po host+port
        return discoveryClient.getInstances(serviceId)
                .filter(inst -> inst.getHost().equals(instanceHost) && inst.getPort() == instancePort)
                .next() // bierzemy tylko pierwszą (powinno być dokładnie jedno dopasowanie)
                .flatMap(inst -> {
                    // 5) Z metadanych instancji bierzemy basepath
                    String basePath = inst.getMetadata().get("basepath");
                    if (!StringUtils.hasText(basePath)) {
                        // jeśli nie ma basepath lub jest pusty – nic nie zmieniamy
                        return chain.filter(exchange);
                    }

                    // 6) Odczytujemy oryginalną ścieżkę (po LB) i doklejamy przed nią wartość basePath
                    String originalPath = lbUri.getPath();      // np. "/api/v1/users"
                    String newPath = "/" + basePath + originalPath; // np. "/my-service/api/v1/users"

                    // 7) Budujemy nowe URI: tylko zmieniamy ścieżkę, reszta (host, port, query itd.) zostaje
                    URI rewritten = UriComponentsBuilder.fromUri(lbUri)
                            .replacePath(newPath)
                            .build(true)
                            .toUri();

                    // 8) Nadpisujemy w atrybutach GATEWAY_REQUEST_URL_ATTR nasze zmienione URI
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, rewritten);

                    // 9) Kontynuujemy łańcuch przekierowania
                    return chain.filter(exchange);
                })
                // Jeśli nie znaleziono instancji (to teoretycznie nie powinno się zdarzyć, bo LoadBalancer już wybrał),
                // to po prostu dalej idziemy bez zmian:
                .switchIfEmpty(chain.filter(exchange));
    }
}
