# PM-004: Testcontainers Silently Skipped Due to Docker API Version Mismatch

**Date:** 2026-03-10
**Severity:** Medium
**Status:** Open (workaround documented, fix pending)
**Author:** Auto-generated from session

## Summary

All `@Testcontainers` integration tests in the project are silently skipped because Testcontainers 1.19.8 (pinned by the Spring Boot 3.3.4 BOM) cannot communicate with Docker Desktop 4.62.0 (Docker API 1.53). The bundled docker-java 3.3.6 client sends a request to the Docker daemon and receives a 400 BadRequest response with zeroed-out fields. Because tests use `@Testcontainers(disabledWithoutDocker = true)`, they are silently skipped rather than failing — meaning the entire integration test suite appears green while providing zero coverage.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-009 (capital protection) implementation complete, 23 unit tests passing |
| +5 min | Attempted to run `AutoShipperApplicationTest` to verify V13 migration with Testcontainers |
| +6 min | Test reported `BUILD SUCCESSFUL` but XML showed `skipped="1"` — test never executed |
| +8 min | Ran with `--info` flag, discovered Testcontainers error: `Could not find a valid Docker environment` |
| +10 min | Error trace showed 400 BadRequest from all Docker client strategies (UnixSocket, Environment, DockerDesktop) |
| +12 min | Identified version mismatch: docker-java 3.3.6 (Testcontainers 1.19.8) vs Docker API 1.53 |
| +15 min | Attempted `DOCKER_API_VERSION=1.44` env var override — did not resolve (docker-java negotiates internally) |
| +20 min | Verified migration manually: ran all 13 Flyway migrations on fresh DB, booted app with `ddl-auto: validate`, confirmed endpoints live |

## Symptom

Running any `@Testcontainers` integration test produces a passing build with all tests skipped:

```
AutoShipperApplicationTest > contextLoads() SKIPPED
BUILD SUCCESSFUL
```

The `--info` output reveals the actual failure:

```
ERROR org.testcontainers.dockerclient.DockerClientProviderStrategy --
  Could not find a valid Docker environment. Attempted configurations were:
    EnvironmentAndSystemPropertyClientProviderStrategy: failed with exception
      BadRequestException (Status 400: {"ID":"","Containers":0,"ContainersRunning":0,
      "ContainersPaused":0,"ContainersStopped":0,"Images":0,"Driver":"",
      "DriverStatus":null,...})
    UnixSocketClientProviderStrategy: failed with exception BadRequestException (Status 400: ...)
    DockerDesktopClientProviderStrategy: failed with exception BadRequestException (Status 400: ...)
```

Docker itself is fully functional — `docker ps`, `docker version`, and `docker exec` all work correctly from the shell.

## Root Cause

Applying the "5 whys":

1. **Why are tests skipped?** → `@Testcontainers(disabledWithoutDocker = true)` sees no valid Docker environment and skips.
2. **Why does Testcontainers think Docker is unavailable?** → All three Docker client provider strategies fail with HTTP 400.
3. **Why does the Docker API return 400?** → docker-java 3.3.6 sends a request that Docker API 1.53 rejects as malformed.
4. **Why is docker-java 3.3.6 used?** → Testcontainers 1.19.8 bundles docker-java 3.3.6, and Testcontainers 1.19.8 is pinned by the Spring Boot 3.3.4 BOM (`spring-boot-dependencies`).
5. **Why hasn't this been caught before?** → Integration tests have always used `disabledWithoutDocker = true`, which silently skips rather than failing. There is no CI gate that asserts integration tests actually executed (vs. being skipped). Docker Desktop auto-updates, so the API version advanced past what docker-java 3.3.6 supports.

The core issue: **Docker API 1.53 (Docker Engine 29.x, released 2026) introduced breaking changes that docker-java 3.3.x cannot handle.** The version negotiation handshake fails at the initial `GET /info` call, returning a 400 with empty fields instead of the expected server metadata.

### Version Matrix

| Component | Version | Ships With |
|-----------|---------|------------|
| Spring Boot BOM | 3.3.4 | Testcontainers 1.19.8 |
| Testcontainers | 1.19.8 | docker-java 3.3.6 |
| docker-java | 3.3.6 | Supports Docker API ≤ 1.45 |
| Docker Desktop | 4.62.0 | Docker API 1.53 (min 1.44) |

## Fix Applied

**No code fix applied yet.** The incident was worked around by verifying the V13 migration manually:

1. Created a fresh `autoshipper_migration_test` database
2. Booted the app with `DB_URL` pointing to the fresh DB
3. Flyway applied all 13 migrations successfully
4. Hibernate `ddl-auto: validate` passed (JPA entity mappings match DB schema)
5. `GET /api/capital/reserve` returned valid JSON
6. `GET /actuator/health` returned `{"status":"UP"}`

### Proposed Fix

Override the Testcontainers BOM version in `build.gradle.kts`:

```kotlin
// In root build.gradle.kts, inside the subprojects block:
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (name != "shared") {
            dependencies {
                // ... existing Spring Boot BOM ...
                // Override Testcontainers to version compatible with Docker API 1.53
                add("testImplementation", platform("org.testcontainers:testcontainers-bom:1.21.0"))
            }
        }
    }
}
```

Alternatively, upgrade Spring Boot from 3.3.4 to 3.4.x+ which ships with a newer Testcontainers.

## Impact

- **All integration tests in the project are silently skipped.** This includes:
  - `AutoShipperApplicationTest` (context load + Flyway migration validation)
  - `VendorBreachIntegrationTest` (vendor module)
  - `CapitalIntegrationTest` (new FR-009 tests)
- **No data corruption or production impact** — this is a local dev/CI test infrastructure issue.
- **False confidence** — `BUILD SUCCESSFUL` with 0 integration test executions gives the appearance of a passing test suite. Any schema drift, event wiring gaps, or cross-module integration bugs introduced since the Docker API upgrade are undetected.

## Lessons Learned

### What went well
- The `--info` flag quickly surfaced the real error behind the silent skip
- Manual migration verification confirmed the V13 migration is correct
- The `disabledWithoutDocker` annotation prevented false failures — but at the cost of silent skips

### What could be improved
- **Silent skips are dangerous.** `disabledWithoutDocker = true` is the test equivalent of `catch (e: Exception) {}` — it suppresses the signal that something is wrong. The test suite should either run integration tests or loudly report that they were skipped.
- **No CI gate for test execution counts.** The build passes regardless of whether 0 or 100 integration tests actually ran. A minimum test execution assertion would catch this.
- **Transitive dependency version management.** The project pins Spring Boot 3.3.4 but doesn't independently manage Testcontainers versions. Docker Desktop auto-updates break the implicit version contract.

## Prevention

- [ ] **Override Testcontainers BOM** to a version compatible with Docker API 1.53 (e.g., 1.21.0+) — or upgrade Spring Boot to 3.4.x+
- [ ] **Add CI assertion for integration test count.** After the test run, parse JUnit XML reports and fail if `tests - skipped < N` (where N is the expected minimum integration test count). This prevents silent-skip regressions.
- [ ] **Replace `disabledWithoutDocker = true` with explicit failure.** Consider `@Testcontainers(disabledWithoutDocker = false)` so Docker unavailability causes a test failure, not a silent skip. In CI, Docker should always be available; locally, developers should see the failure and know to start Docker.
- [ ] **Pin Docker Desktop version in dev setup docs.** Document the minimum/maximum compatible Docker Desktop version, or add a pre-test check script that validates `docker version --format '{{.Server.APIVersion}}'` against a supported range.
- [ ] **Consider adding a Gradle task** that runs `docker info` before the test phase and fails fast with a clear message if Docker is unavailable or incompatible, rather than letting Testcontainers discover the problem per-test-class.
