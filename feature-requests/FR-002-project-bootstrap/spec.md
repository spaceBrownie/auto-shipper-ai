# FR-002: Project Bootstrap

## Problem Statement

No runnable project exists yet. Before any business logic can be written, the Spring Boot 3.x multi-module project must be initialized with the correct package structure, database connectivity, schema migration tooling, and build configuration. Without this foundation, all subsequent FRs are blocked.

## Business Requirements

- The project must be a Kotlin + Spring Boot 3.x application buildable with Gradle (Kotlin DSL)
- The modular monolith structure must be established at the package level: `catalog`, `pricing`, `vendor`, `fulfillment`, `capital`, `compliance`, `portfolio`, `shared`
- PostgreSQL must be the persistence layer with connection pool configured via Spring datasource
- Flyway must manage all schema migrations — no Hibernate `ddl-auto=create`
- Application must start successfully with a health endpoint at `GET /actuator/health`
- Environment-based configuration must be supported via `application.yml` + environment variable overrides

## Success Criteria

- `./gradlew bootRun` starts the application without errors
- `./gradlew test` runs and passes all bootstrap tests
- `GET /actuator/health` returns `{"status":"UP"}`
- PostgreSQL connection is verified on startup
- Flyway runs `V1__init.sql` baseline migration on startup
- Package structure exists for all 8 bounded contexts
- `shared` module is a Gradle subproject importable by other modules

## Non-Functional Requirements

- Java 21+ (virtual threads enabled)
- Spring Boot 3.x with Kotlin coroutines support
- Micrometer + Prometheus metrics endpoint exposed at `/actuator/prometheus`
- Logback structured JSON logging for production profile
- All secrets (DB password, API keys) sourced from environment variables — no hardcoded values

## Dependencies

- FR-001 (shared-domain-primitives) — shared module must be importable at bootstrap
