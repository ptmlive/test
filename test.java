    CorsConfiguration config = new CorsConfiguration();

    config.setAllowCredentials(true);
    config.addAllowedOrigin("*");
    config.setAllowedHeaders(Arrays.asList(
        "Origin", "Content-Type", "Accept", "Authorization", "X-Requested-With"
    ));
    config.setAllowedMethods(Arrays.asList(
        "POST", "GET", "OPTIONS", "DELETE", "PUT", "PATCH"
    ));
    config.setExposedHeaders(Arrays.asList("*"));
    config.setMaxAge(3600L); // czas w sekundach

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    return new CorsWebFilter(source);
