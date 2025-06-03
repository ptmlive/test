@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    @Value("${scp.http.security.authenticated:}")
    private final List<String> authenticatedEndpoints;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerHttpSecurity.AuthorizeExchangeSpec exchanges = http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange();

        if (!authenticatedEndpoints.isEmpty()) {
            exchanges = exchanges
                    .pathMatchers(authenticatedEndpoints.toArray(new String[0])).authenticated();
        }

        exchanges.anyExchange().permitAll();

        return http.build();
    }
}
