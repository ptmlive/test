import com.example.gateway.config.AuthorizationConfiguration;
import com.example.gateway.config.BasicAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(101)
public class OverrideAuthorizationHeaderFilter implements GlobalFilter {

    private final AuthorizationConfiguration authorizationConfiguration;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            log.debug("OverrideAuthorizationHeaderFilter: no route found – skipping");
            return chain.filter(exchange);
        }
        String serviceId = route.getUri().getHost();
        if (!authorizationConfiguration.isBasicAuthForService(serviceId)) {
            log.debug("OverrideAuthorizationHeaderFilter: basic auth not required for '{}' – skipping", serviceId);
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(Authentication::isAuthenticated)
            .flatMap(auth -> {
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
                List<?> authoritiesClaim = jwt.getClaimAsStringList("authorities");
                Set<String> required = basicAuthService.getRequiredAuthorities(exchange.getRequest());
                boolean allowed = required.isEmpty() || authoritiesClaim.stream().map(Object::toString).anyMatch(required::contains);
                if (!allowed) {
                    log.debug("OverrideAuthorizationHeaderFilter: insufficient authorities for '{}' – returning 403", serviceId);
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
                String headerValue = basicAuthService.getBasicAuthorizationHeader();
                ServerWebExchange mutated = exchange.mutate()
                    .request(exchange.getRequest().mutate()
                        .header(HttpHeaders.AUTHORIZATION, headerValue)
                        .build())
                    .build();
                log.info("OverrideAuthorizationHeaderFilter: added Basic Authorization for '{}'", serviceId);
                return chain.filter(mutated);
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("OverrideAuthorizationHeaderFilter: user not authenticated – skipping");
                return chain.filter(exchange);
            }));
    }
}
