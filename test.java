spring:
  cloud:
    gateway:
      globalcors:
        corsConfigurations:
          '[/**]':
            allowCredentials: true
            allowedOriginPatterns:
              - "*"
            allowedHeaders:
              - Origin
              - Content-Type
              - Accept
              - Authorization
              - X-Requested-With
            allowedMethods:
              - GET
              - POST
              - PUT
              - PATCH
              - DELETE
              - OPTIONS
            exposedHeaders:
              - "*"
            maxAge: 3600
