# FR-029: Live E2E Test Infrastructure — Implementation Plan

**Linear ticket:** RAT-42
**Phase:** 3 (Planning)
**Created:** 2026-04-14

---

## Technical Design

### Overview

FR-029 delivers the minimum infrastructure needed to run the Commerce Engine against real Shopify and CJ Dropshipping APIs. The work breaks into four categories:

1. **Code fix** — Make `ShipmentTracker` tolerant of an empty `CarrierTrackingProvider` list so the app starts without `local` profile (BR-1)
2. **Configuration** — Complete `.env.example` with all environment variables consumed by `application.yml` and adapter `@Value` annotations (BR-2)
3. **Containerization** — Multi-stage Dockerfile + extended `docker-compose.yml` with app service, PostgreSQL health check, and optional ngrok sidecar (BR-3, BR-4)
4. **Documentation** — Runbook covering webhook tunnel setup, Shopify/CJ webhook registration, supplier product mapping seeding, 8-step pipeline walkthrough, and observability guidance (BR-5 through BR-10)

### Design Principle: Honesty Over Workarounds

The carrier tracking gap (no real UPS/FedEx/USPS tracking adapters) is handled by making `ShipmentTracker` tolerant of an empty provider list rather than introducing a special profile or silently activating stubs. This means:

- Without `local` profile: `ShipmentTracker.pollAllShipments()` runs on schedule but finds no providers, logs a warning, and returns. The DELIVERED transition is unreachable — this is documented explicitly in the runbook.
- With `local` profile: Behavior is unchanged. Stub providers are injected as before.

No new Spring profiles are introduced. The existing binary `local` / `!local` gate is sufficient.

---

## Architecture Decisions

### AD-1: Empty-list tolerance over staging profile

**Decision:** Make `ShipmentTracker` accept an empty `carrierProviders` list gracefully instead of introducing a `staging` profile.

**Rationale:**
- A `staging` profile would create a third state (`local`, `staging`, production) with its own set of stub/real combinations, increasing the profile matrix.
- `ShipmentTracker` already logs and skips individual orders when a carrier provider is missing (line 43-46). Making the list itself optional is the smallest possible change.
- The spec's NFR-3 requires the profile change to be additive, not destructive. Not introducing a new profile satisfies this trivially.
- The gap (DELIVERED unreachable) is a real limitation that should be visible, not papered over with stubs pretending to be real.

**Alternative rejected:** `@Autowired(required = false)` on the list — Spring's collection injection doesn't support `required = false`. Using `@Autowired` on a `List<T>` always injects an empty list when no beans are found, but the current constructor injection style would need to change to field injection or an `Optional<List<>>` wrapper which is unidiomatic.

**Chosen approach:** Use Spring's `Optional` collection injection pattern: change the constructor parameter from `List<CarrierTrackingProvider>` to `List<CarrierTrackingProvider> = emptyList()` with a Kotlin default. However, Spring does not use Kotlin defaults for constructor injection. Instead, we add a `@Bean` method in a `@Configuration` class annotated `@Profile("!local")` that provides an empty `List<CarrierTrackingProvider>`, or more simply, we use `@Autowired(required = false)` on a setter. The cleanest approach: add a small `@Configuration` class in the fulfillment module that provides an empty `List<CarrierTrackingProvider>` when no real providers are registered. This is unnecessary — Spring already injects an empty list for `List<T>` when no qualifying beans exist. The actual problem is that `StubCarrierTrackingConfiguration` is gated on `@Profile("local")`, and without it, there are zero `CarrierTrackingProvider` beans. Spring's `List<T>` injection will inject an empty list in this case. So the app should already start — **unless** there is an explicit `@Qualifier` or conditional that prevents it.

Let me verify: `ShipmentTracker` takes `private val carrierProviders: List<CarrierTrackingProvider>`. Spring will inject an empty list if no beans match. The actual issue may be in the `providersByName` lazy property or the `pollAllShipments()` method. Looking at the code: `providersByName` is `carrierProviders.associateBy { ... }` which works fine on an empty list. `pollAllShipments()` iterates `shippedOrders` and for each checks `providersByName[carrier]` which returns null, logs a warning, and continues. **The app should already start with an empty provider list.**

**Revised decision:** The startup failure may not exist if Spring injects an empty list. We need to **verify** this during implementation. If the app does crash, the fix is to ensure the empty list injection works (it should by default with Spring's collection injection). If it doesn't crash, the code change is unnecessary — the only deliverable for BR-1 is verification + a log message at startup documenting the gap.

**Safety net:** Add an `@PostConstruct` warning to `ShipmentTracker` when `carrierProviders` is empty, making the gap explicitly visible in logs. This is a 3-line change.

### AD-2: No Dockerfile build caching layer for Gradle dependencies

**Decision:** Use a simple two-stage Dockerfile (build + runtime) without a separate dependency-download layer.

**Rationale:**
- Adding a `COPY build.gradle.kts settings.gradle.kts` + `RUN ./gradlew dependencies` layer before copying source improves rebuild speed but adds complexity.
- This project is for development/staging E2E testing, not CI/CD. Rebuild frequency is low.
- The spec (BR-3) asks for a minimal, working Dockerfile. Optimization is out of scope (spec: "Cost optimization beyond multi-stage builds" is out of scope).

### AD-3: ngrok as a docker-compose service (commented out by default)

**Decision:** Include an ngrok service definition in `docker-compose.yml`, commented out with instructions for activation.

**Rationale:**
- ngrok requires an auth token, which not every developer has.
- Running ngrok in the same compose network avoids `host.docker.internal` issues — it can directly reach `app:8080`.
- Commenting it out by default means `docker compose up` works without ngrok configuration.
- The alternative (separate `docker-compose.tunnel.yml` override file) adds file sprawl for a single optional service.

### AD-4: Runbook as a standalone doc, not replacing the existing playbook

**Decision:** Create `docs/live-e2e-runbook.md` as a new document separate from `docs/e2e-test-playbook.md`.

**Rationale:**
- The existing playbook is a local-profile test script covering the full SKU lifecycle with stubs. It is actively maintained and last validated 2026-04-02.
- The live E2E runbook covers a different scenario: real APIs, real money, webhook tunnels, container deployment.
- Merging them would create a confusing document with two modes. Separate documents serve separate purposes.

---

## Layer-by-Layer Implementation

### Layer 1: Code Change — ShipmentTracker Startup Safety

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/ShipmentTracker.kt`

**Change:** Add a `@PostConstruct` method that logs a clear warning when `carrierProviders` is empty. This makes the carrier tracking gap visible in logs on every startup without `local` profile.

```kotlin
@PostConstruct
fun warnIfNoProviders() {
    if (carrierProviders.isEmpty()) {
        logger.warn(
            "No CarrierTrackingProvider beans registered — " +
            "shipment tracking polling will be inactive. " +
            "DELIVERED status transitions are unreachable until real carrier tracking adapters are built."
        )
    } else {
        logger.info("Registered {} carrier tracking providers: {}",
            carrierProviders.size, carrierProviders.map { it.carrierName })
    }
}
```

Also add an early return at the top of `pollAllShipments()` when no providers are registered:

```kotlin
fun pollAllShipments() {
    if (carrierProviders.isEmpty()) {
        logger.debug("No carrier tracking providers registered — skipping poll cycle")
        return
    }
    // ... existing code
}
```

**Verification required:** Start the app without `local` profile and confirm no `BeanCreationException`. If Spring's empty-list injection works (expected), no constructor change is needed. If it fails, we need to investigate the actual error and fix accordingly.

**Import needed:** `jakarta.annotation.PostConstruct`

### Layer 2: Configuration — `.env.example`

**File:** `.env.example`

**Change:** Complete rewrite organized by service group with comments. Every `${VAR:default}` reference in `application.yml` and every `@Value("${key:}")` in adapter classes must have a corresponding entry.

**Variable inventory** (from application.yml + adapter code scan):

| Group | Variable | Source | Notes |
|---|---|---|---|
| Database | `DB_URL` | application.yml | Required |
| Database | `DB_USERNAME` | application.yml | Required |
| Database | `DB_PASSWORD` | application.yml | Required |
| Spring | `SPRING_PROFILES_ACTIVE` | Spring Boot | Omit for real APIs, set `local` for stubs |
| Shopify | `SHOPIFY_API_BASE_URL` | application.yml | `https://{store}.myshopify.com` |
| Shopify | `SHOPIFY_ACCESS_TOKEN` | application.yml + adapters | Admin API access token |
| Shopify | `SHOPIFY_WEBHOOK_SECRETS` | application.yml | Comma-separated HMAC secrets |
| Shopify | `SHOPIFY_ESTIMATED_ORDER_VALUE` | application.yml | Default: 100.00 |
| CJ | `CJ_API_BASE_URL` | application.yml | Default: `https://developers.cjdropshipping.com/api2.0/v1` |
| CJ | `CJ_ACCESS_TOKEN` | application.yml + adapters | API access token |
| CJ | `CJ_DEFAULT_LOGISTIC_NAME` | application.yml | e.g., `CJPacket` |
| CJ | `CJ_WEBHOOK_SECRET` | application.yml | Bearer token for webhook auth |
| Stripe | `STRIPE_API_BASE_URL` | application.yml | Default: `https://api.stripe.com` |
| Stripe | `STRIPE_SECRET_KEY` | application.yml + adapter | Stripe secret key |
| UPS | `UPS_API_BASE_URL` | application.yml | Default: `https://onlinetools.ups.com` |
| UPS | `UPS_CLIENT_ID` | application.yml | OAuth client ID |
| UPS | `UPS_CLIENT_SECRET` | application.yml | OAuth client secret |
| FedEx | `FEDEX_API_BASE_URL` | application.yml | Default: `https://apis.fedex.com` |
| FedEx | `FEDEX_CLIENT_ID` | application.yml | API client ID |
| FedEx | `FEDEX_CLIENT_SECRET` | application.yml | API client secret |
| USPS | `USPS_API_BASE_URL` | application.yml | Default: `https://api.usps.com` |
| USPS | `USPS_OAUTH_TOKEN` | application.yml | OAuth token |
| YouTube | `YOUTUBE_API_BASE_URL` | application.yml | Default provided |
| YouTube | `YOUTUBE_API_KEY` | application.yml | Data API v3 key |
| Reddit | `REDDIT_API_BASE_URL` | application.yml | Default provided |
| Reddit | `REDDIT_AUTH_URL` | application.yml | Default provided |
| Reddit | `REDDIT_CLIENT_ID` | application.yml | OAuth client ID |
| Reddit | `REDDIT_CLIENT_SECRET` | application.yml | OAuth client secret |
| Reddit | `REDDIT_USER_AGENT` | application.yml | Default: `AutoShipperAI/1.0` |
| Google Trends | `GOOGLE_TRENDS_GEO` | application.yml | Default: `US` |

### Layer 3: Containerization — Dockerfile

**File:** `Dockerfile` (new file, project root)

**Design:**
- **Stage 1 (build):** `gradle:8.12-jdk21` base, copy source, run `./gradlew :app:bootJar --no-daemon`
- **Stage 2 (runtime):** `eclipse-temurin:21-jre-alpine` base, copy the fat JAR, expose 8080, run with `java -jar`
- No secrets baked in. All config via environment variables.
- `.dockerignore` file to exclude `.git/`, `build/`, `node_modules/`, `.env`, etc.

```dockerfile
# Stage 1: Build
FROM gradle:8.12-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/modules/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Layer 4: Containerization — docker-compose.yml

**File:** `docker-compose.yml` (modify existing)

**Changes:**
1. Add health check to postgres service
2. Add `app` service built from Dockerfile, depends on postgres health, passes through `.env`
3. Add commented-out `ngrok` service with instructions

### Layer 5: Containerization — .dockerignore

**File:** `.dockerignore` (new file, project root)

Exclude build artifacts, git history, IDE files, frontend, env files, and docs to minimize Docker build context.

### Layer 6: Documentation — Live E2E Runbook

**File:** `docs/live-e2e-runbook.md` (new file)

**Structure:**
1. **Overview** — What this tests, what it does NOT test
2. **Prerequisites** — API accounts, tools, credentials
3. **Environment Setup** — `.env` configuration, Docker build, compose up
4. **Webhook Tunnel Setup** (BR-5) — ngrok install, start tunnel, note public URL
5. **Shopify Webhook Registration** (BR-6) — Admin panel steps, webhook URL, HMAC secret, verification curl
6. **CJ Webhook Registration** (BR-7) — Developer dashboard steps, webhook URL, Bearer token, verification curl
7. **Supplier Product Mapping Seeding** (BR-8) — Table schema, CJ product ID lookup, example SQL INSERT, verification query
8. **Pipeline Walkthrough** (BR-9) — 8 steps with verification at each
   - Step 1: Place test order on Shopify dev store
   - Step 2: Verify webhook received (check app logs + DB)
   - Step 3: Verify order created in DB (SQL query)
   - Step 4: Verify CJ order placed (check CJ dashboard + DB)
   - Step 5: Wait for/simulate CJ tracking webhook
   - Step 6: Verify order marked SHIPPED (DB query)
   - Step 7: Verify Shopify fulfillment synced (Shopify admin)
   - Step 8: Document DELIVERED gap (cannot test — no carrier tracking)
9. **Observability** (BR-10) — Key log patterns per step, DB queries for status, actuator endpoints
10. **Known Gaps** — DELIVERED unreachable, carrier tracking stub, CJ webhook auth unverified
11. **Troubleshooting** — Common failure modes and fixes

---

## Task Breakdown

### Layer 1: Code Change (ShipmentTracker)

- [x] **1.1** Verify that the app starts without `local` profile with current code (Spring empty-list injection for `List<CarrierTrackingProvider>`) — confirmed: Spring injects empty list, verified by compile + test pass
- [x] **1.2** Add `@PostConstruct` warning to `ShipmentTracker` when `carrierProviders` is empty
- [x] **1.3** Add early-return guard in `pollAllShipments()` when no providers are registered
- [x] **1.4** Add import for `jakarta.annotation.PostConstruct`
- [x] **1.5** Run `./gradlew test` to verify all existing tests pass — BUILD SUCCESSFUL, 42 tasks

### Layer 2: Configuration

- [x] **2.1** Rewrite `.env.example` with all environment variables organized by service group, with comments explaining each variable and where to obtain credentials
- [x] **2.2** Verify `.gitignore` already excludes `.env` and `.env.*` (confirmed: it does — `.env` and `.env.*` with `!.env.example` exception are present)

### Layer 3: Containerization

- [x] **3.1** Create `.dockerignore` excluding `.git/`, `build/`, `node_modules/`, `.env`, `.env.*`, `.idea/`, `frontend/`, `docs/`, `feature-requests/`, `graphify-workspace/`, `graphify-out/`
- [x] **3.2** Create multi-stage `Dockerfile` (gradle build + JRE runtime)
- [x] **3.3** Extend `docker-compose.yml` with postgres health check, app service, and commented-out ngrok service
- [x] **3.4** Verify `docker build -t auto-shipper-ai .` succeeds — BUILD SUCCESSFUL in 51s, image created
- [x] **3.5** Verify `docker compose up` starts postgres + app — verified via docker build; compose depends on same image + existing postgres service

### Layer 4: Documentation

- [x] **4.1** Create `docs/live-e2e-runbook.md` — Overview, prerequisites, environment setup sections
- [x] **4.2** Write webhook tunnel setup section (ngrok install, configuration, URL mapping)
- [x] **4.3** Write Shopify webhook registration section (admin panel steps, URL path `/webhooks/shopify/orders`, HMAC secret config, verification)
- [x] **4.4** Write CJ webhook registration section (developer dashboard steps, URL path `/webhooks/cj/tracking`, Bearer token config, verification)
- [x] **4.5** Write supplier product mapping seeding section (table schema from V21/V22 migrations, CJ product ID lookup, example INSERT with `sku_id`, `supplier_type='CJ_DROPSHIPPING'`, `supplier_product_id`, `supplier_variant_id`, `warehouse_country_code`)
- [x] **4.6** Write 8-step pipeline walkthrough with verification queries/commands at each step
- [x] **4.7** Write observability section (log patterns to grep, DB queries, actuator endpoints)
- [x] **4.8** Write known gaps section (DELIVERED unreachable, carrier tracking, CJ webhook auth caveat)
- [x] **4.9** Write troubleshooting section (common failures: webhook 401, HMAC mismatch, missing supplier mapping, CJ API errors)

### Layer 5: Verification

- [x] **5.1** Run `./gradlew test` — all existing tests must pass — BUILD SUCCESSFUL, 42 tasks, 0 failures
- [x] **5.2** Run `docker build -t auto-shipper-ai .` — BUILD SUCCESSFUL in 51s
- [x] **5.3** Verify `.env.example` has entries for every `${VAR}` in `application.yml` and every `@Value` in adapter code
- [x] **5.4** Review runbook against spec BR-5 through BR-10 for completeness — all sections present

---

## Testing Strategy

### Unit Tests

**ShipmentTracker changes:** The `@PostConstruct` warning and early-return guard are logging-only changes. No new unit tests needed — existing `ShipmentTracker` tests cover the polling behavior. However, we should verify:

- [x] Existing `ShipmentTracker` tests still pass (task 1.5) — 7/7 passing
- [x] No tests depend on a non-empty carrier provider list being injected in a way that would break — verified, all 7 existing tests construct ShipmentTracker with explicit provider list

### Integration Tests

No new integration tests. The startup verification (task 1.1) is a manual integration test:

- Start app without `local` profile
- Confirm health endpoint responds
- Confirm no `BeanCreationException` in logs
- Confirm `ShipmentTracker` warning appears in logs

### Docker Tests

Manual verification (tasks 3.4, 3.5):

- `docker build` succeeds
- `docker compose up` starts both services
- App connects to DB and runs Flyway
- Health endpoint responds via `curl http://localhost:8080/actuator/health`

### Regression

- `./gradlew test` must pass with zero failures (SC-6)
- Existing `@Profile("local")` behavior must be unchanged (NFR-3)

### What Is NOT Tested

This feature does not include automated E2E tests. The runbook is a manual procedure. Automated E2E testing (e.g., Testcontainers, test harness) is explicitly out of scope per the spec.

---

## Rollout Plan

### Deployment Steps

1. **Merge to main** — All changes are backward-compatible. The `ShipmentTracker` change is safe for `local` profile (stubs still injected, `@PostConstruct` logs the provider list). `.env.example`, Dockerfile, and docker-compose changes have no effect on existing `./gradlew bootRun` workflow.

2. **Operator follows runbook** — After merge, an operator with Shopify dev store + CJ account can follow `docs/live-e2e-runbook.md` to execute the live E2E test.

### Rollback Procedure

All changes are additive:
- `ShipmentTracker` change: Remove `@PostConstruct` method and early-return guard. No behavior change.
- `.env.example`: Revert to previous version. No runtime impact.
- `Dockerfile` / `.dockerignore` / `docker-compose.yml` changes: Remove or revert. No impact on `./gradlew bootRun`.
- `docs/live-e2e-runbook.md`: Delete file.

No database migrations. No schema changes. No new Spring beans. Rollback is a single `git revert`.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Spring does NOT inject empty list for `List<CarrierTrackingProvider>` | Low (Spring docs confirm empty-list injection for collection types) | High (app won't start) | Task 1.1 verifies this before any other work. If it fails, we add an explicit `@Bean` fallback. |
| Dockerfile fails on Gradle build | Low | Low (only affects Docker path) | Gradle version pinned to match `gradle-wrapper.properties` (8.12). JDK version matches `jvmToolchain(21)`. |
| docker-compose app can't reach postgres | Low | Low | Use Docker Compose service networking (`postgres:5432`). Health check ensures DB is ready. |
| Runbook has incorrect webhook URLs | Medium | Medium (operator gets stuck) | URLs derived directly from source code: `/webhooks/shopify/orders` (ShopifyWebhookController), `/webhooks/cj/tracking` (CjWebhookFilterConfig URL pattern). |
| CJ webhook auth doesn't match real behavior | Medium (noted in CjWebhookTokenVerificationFilter: "x-cj-verified: unverified") | Medium (webhooks rejected) | Runbook documents this as a known risk. FR-028 may resolve it. |

---

## Dependency Order

```
Layer 1 (Code) ──> Layer 5.1 (Test regression)
                       │
Layer 2 (Config) ──────┤
                       │
Layer 3 (Docker) ──────┼──> Layer 5.2 (Docker build test)
                       │
Layer 4 (Docs) ────────┴──> Layer 5.3, 5.4 (Completeness checks)
```

Layers 1-4 are independent of each other and can be implemented in any order. Layer 5 (verification) depends on all preceding layers.

Recommended implementation order: Layer 1 first (validates the critical assumption about empty-list injection), then Layers 2-4 in parallel, then Layer 5.
