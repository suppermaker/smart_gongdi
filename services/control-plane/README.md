# Control Plane

Java 17 / Spring Boot 3 modular monolith for authoritative SitePilot business state. PostgreSQL schema is managed by Flyway and integration tests run against Testcontainers PostgreSQL.

Run `mvn test` (Docker required for integration tests). Trusted request context is supplied through `X-SitePilot-Tenant`, `X-SitePilot-Project`, and `X-SitePilot-Actor`.
