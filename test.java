return discoveryClient.getServices()
    .flatMap(serviceNames ->
        Flux.fromIterable(serviceNames)
            .flatMap(discoveryClient::getInstances)
            .filter(inst -> {
                String serviceId = inst.getServiceId().toLowerCase();
                return originalPath.startsWith("/" + serviceId);
            })
            .next()
            .defaultIfEmpty(null)
            .flatMap(serviceInstance -> {
                if (serviceInstance == null) {
                    log.info("No matching service found for path: {}", originalPath);
                    return chain.filter(exchange);
                }

                String basePath = serviceInstance.getMetadata().getOrDefault("basePath", "");

                if (basePath.isEmpty()) {
                    log.info("Service '{}' is on-prem. Passing path through unchanged: {}", serviceInstance.getServiceId(), originalPath);
                    return chain.filter(exchange);
                }

                String servicePrefix = "/" + serviceInstance.getServiceId().toLowerCase();
                String adjustedPath = originalPath.replaceFirst(servicePrefix, basePath);

                log.info("Rewriting path for service '{}': {} → {}", serviceInstance.getServiceId(), originalPath, adjustedPath);

                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .path(adjustedPath)
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            })
    );
