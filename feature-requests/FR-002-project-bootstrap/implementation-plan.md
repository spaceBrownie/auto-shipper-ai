# FR-002: Project Bootstrap — Implementation Plan

## Technical Design

A Kotlin + Spring Boot 3.x Gradle multi-project build. The root project hosts all bounded context modules as subprojects. Each bounded context is a package under a shared Spring Boot application context in Phase 1 (modular monolith). Flyway manages schema versions; no Hibernate DDL generation.

```
auto-shipper-ai/
├── build.gradle.kts         (root)
├── settings.gradle.kts      (declares subprojects)
├── gradle.properties
├── shared/
│   └── build.gradle.kts
├── catalog/
│   └── build.gradle.kts
├── pricing/
├── vendor/
├── fulfillment/
├── capital/
├── compliance/
├── portfolio/
└── app/                     (Spring Boot entry point, wires all modules)
    ├── src/main/kotlin/com/autoshipper/AutoShipperApplication.kt
    ├── src/main/resources/
    │   ├── application.yml
    │   └── db/migration/
    │       └── V1__init.sql
    └── build.gradle.kts
```

## Architecture Decisions

- **Gradle Kotlin DSL over Maven**: Type-safe build scripts, better IDE support.
- **Modular monolith in a single Spring Boot `app` module**: All bounded context modules compile into one deployable JAR in Phase 1. Modules communicate via Spring events, not HTTP.
- **Flyway over Hibernate DDL**: Schema versioning is an explicit, reviewable artifact — not auto-generated.
- **Java 21 with virtual threads**: `spring.threads.virtual.enabled=true` enables virtual threads for Tomcat, removing need for reactive stack in Phase 1.
- **Separate `app` module**: Isolates the Spring Boot entry point from business logic modules, making future microservice extraction cleaner.

## Layer-by-Layer Implementation

### Config Layer
- `application.yml`: datasource, Flyway, Actuator, virtual threads, logging
- `application-local.yml`: local dev overrides (H2 or local Postgres)
- Environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, all API keys

### Domain Layer
- `AutoShipperApplication.kt`: `@SpringBootApplication` entry point
- Package scanning configured to pick up all bounded context modules

### Common Layer
- `V1__init.sql`: baseline Flyway migration (schema skeleton — empty tables or initial structure)
- Actuator health and Prometheus metrics configured

## Task Breakdown

### Config Layer
- [x] Initialize Gradle multi-project build (`settings.gradle.kts`, root `build.gradle.kts`)
- [x] Configure Kotlin + Spring Boot 3.x plugin versions in `gradle.properties`
- [x] Create `app` subproject `build.gradle.kts` with Spring Boot, JPA, Flyway, Actuator, Micrometer dependencies
- [x] Create `application.yml` with datasource, Flyway, virtual threads, Actuator config
- [x] Create `application-local.yml` for local dev profile
- [x] Add `.env.example` documenting all required environment variables

### Domain Layer
- [x] Create `AutoShipperApplication.kt` with `@SpringBootApplication`
- [x] Configure component scan to include all bounded context packages
- [x] Create empty package structure for all 8 bounded contexts

### Common Layer (Gradle subprojects)
- [x] Create `shared/build.gradle.kts` (Kotlin stdlib only)
- [x] Create stub `build.gradle.kts` for `catalog`, `pricing`, `vendor`, `fulfillment`, `capital`, `compliance`, `portfolio`
- [x] Wire all subprojects as dependencies of `app` in `app/build.gradle.kts`

### Persistence
- [x] Write `V1__init.sql` as empty baseline migration
- [x] Verify Flyway runs cleanly on startup
- [x] Configure `spring.jpa.hibernate.ddl-auto=validate`

### Observability
- [x] Expose `/actuator/health` with UP status
- [x] Expose `/actuator/prometheus` with Micrometer metrics
- [x] Configure Logback JSON appender for production profile

## Testing Strategy

- Smoke test: `@SpringBootTest` that loads full context and asserts no startup errors
- `GET /actuator/health` returns `{"status":"UP"}` via `TestRestTemplate`
- Flyway migration test: embedded Postgres (Testcontainers) runs `V1__init.sql` cleanly
- All tests run with `./gradlew :app:test`

## Rollout Plan

1. Initialize Gradle build and verify `./gradlew build` produces a JAR
2. Configure PostgreSQL connection (Testcontainers for CI, real DB for local)
3. Run Flyway baseline migration
4. Start app and verify health endpoint
5. No business logic in this FR — just the runnable skeleton
