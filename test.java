@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    @Value("${scp.http.security.authenticated:}")
    private final List<String> authenticatedEndpoints;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeHttpRequests(auth -> {
                    if (!authenticatedEndpoints.isEmpty()) {
                        auth.pathMatchers(authenticatedEndpoints.toArray(new String[0])).authenticated();
                    }
                    auth.anyExchange().permitAll();
                })
                .build();
    }
}
