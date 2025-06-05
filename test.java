package com.example.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.reactive.CorsWebFilter;
import java.util.Arrays;

@Slf4j
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(Arrays.asList("*"));
        config.setAllowedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization", "X-Requested-With"));
        config.setAllowedMethods(Arrays.asList("POST", "GET", "OPTIONS", "DELETE", "PUT", "PATCH"));
        config.setExposedHeaders(Arrays.asList("*"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.debug("CORS Configuration:");
        log.debug(" AllowCredentials: {}", config.getAllowCredentials());
        log.debug(" AllowedOriginPatterns: {}", config.getAllowedOriginPatterns());
        log.debug(" AllowedHeaders: {}", config.getAllowedHeaders());
        log.debug(" AllowedMethods: {}", config.getAllowedMethods());
        log.debug(" ExposedHeaders: {}", config.getExposedHeaders());
        log.debug(" MaxAge: {}", config.getMaxAge());

        return new CorsWebFilter(source);
    }
}
