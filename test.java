@Bean
public CorsWebFilter corsWebFilter() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowCredentials(true);
    // zamiast "*", podaj tu dokładny adres frontu, np:
    config.addAllowedOrigin("http://localhost:4200");
    config.setAllowedHeaders(Arrays.asList(
        "Origin", "Content-Type", "Accept", "Authorization", "X-Requested-With"
    ));
    config.setAllowedMethods(Arrays.asList(
        "POST", "GET", "OPTIONS", "DELETE", "PUT", "PATCH"
    ));
    config.setExposedHeaders(Arrays.asList("*"));
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    return new CorsWebFilter(source);
}
