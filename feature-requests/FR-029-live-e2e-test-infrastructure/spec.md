# FR-029: Live E2E Test Infrastructure

**Linear ticket:** RAT-42
**Status:** Specification (Phase 2)
**Created:** 2026-04-14

---

## Problem Statement

The Commerce Engine has never been run against real external APIs. All 17 adapter pairs (real vs stub) are gated by `@Profile("local")` / `@Profile("!local")`, but no one has ever started the application without the `local` profile. There is no Dockerfile, no deployment configuration, no webhook tunnel setup, and no runbook for executing the order pipeline end-to-end with real money and real APIs.

### The app cannot start without `local` profile

`ShipmentTracker` depends on `List<CarrierTrackingProvider>`, which is only satisfied by `StubCarrierTrackingConfiguration` under the `local` profile. No real `CarrierTrackingProvider` implementations exist (UPS, FedEx, USPS tracking APIs have rate adapters but no tracking adapters). Starting the app without `local` will fail with a missing bean error. This is a hard blocker for any non-local deployment.

### No containerization or deployment path

There is no Dockerfile, no container build step, and no deployment configuration. The only way to run the app is `./gradlew bootRun` locally. Testing against real APIs requires the app to be accessible from the internet (for Shopify and CJ webhooks), which requires a tunnel (ngrok) or cloud deployment, neither of which is configured.

### `.env.example` is incomplete

The current `.env.example` is missing critical environment variables needed for the order pipeline: `SHOPIFY_API_BASE_URL`, `SHOPIFY_ACCESS_TOKEN`, `SHOPIFY_WEBHOOK_SECRETS`, `CJ_API_BASE_URL`, `CJ_DEFAULT_LOGISTIC_NAME`, `CJ_WEBHOOK_SECRET`, `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET`, `FEDEX_CLIENT_ID`, `FEDEX_CLIENT_SECRET`, `USPS_OAUTH_TOKEN`. An operator following `.env.example` would be missing half the configuration needed to run with real APIs.

### No webhook registration guidance

Shopify and CJ both require webhook registration (pointing to the app's public URL). Shopify webhooks need HMAC secrets configured; CJ webhooks need a Bearer token. There is no documentation on how to register these webhooks, what URLs to use, or how to verify they are working.

### The full pipeline has untested gaps

Even if the app could start, the pipeline has known gaps that would prevent a complete end-to-end test:
1. **DELIVERED status unreachable** -- `OrderEventListener` fires on `OrderFulfilled` (emitted when an order transitions to DELIVERED), but the only path to DELIVERED is through `ShipmentTracker.pollAllShipments()`, which requires `CarrierTrackingProvider` beans that do not exist outside `local` profile.
2. **`supplier_product_mappings` table requires manual seeding** -- CJ order placement looks up which CJ product ID maps to which internal SKU. There is no automated way to populate this; it requires a manual database insert.
3. **CJ webhook authentication is unverified** -- `CjWebhookTokenVerificationFilter` assumes Bearer token auth, which may not match CJ's actual webhook delivery mechanism (this may be resolved by FR-028, but the filter must work for the E2E test to succeed).

### Why this matters

Without the ability to run the order pipeline against real APIs, the system's revenue-critical path (Shopify order received -> CJ order placed -> tracking webhook received -> Shopify fulfillment synced -> capital recorded) remains entirely hypothetical. Every adapter, webhook filter, and event listener has only been tested against stubs and fabricated fixtures. PM-020 already proved that fabricated fixtures can be 100% wrong. The E2E test is the minimum viable evidence that the system actually works.

## Business Requirements

### BR-1: Application starts and runs without `local` profile

The application must be startable with a Spring profile configuration that activates real adapters for Shopify and CJ while gracefully handling the absence of carrier tracking provider implementations. The app must not crash on startup due to missing beans. The solution must not create fake "real" carrier tracking adapters -- it must be honest about what is stubbed and what is real.

### BR-2: `.env.example` documents all required environment variables

`.env.example` must list every environment variable consumed by `application.yml` and adapter classes, organized by service. Each variable must include a comment explaining what it is and where to obtain it. Variables must be grouped into:
- **Required for any deployment** (DB, Spring config)
- **Required for Shopify integration** (API base URL, access token, webhook secrets)
- **Required for CJ integration** (API base URL, access token, logistic name, webhook secret)
- **Required for carrier rate APIs** (UPS, FedEx, USPS credentials)
- **Required for payment processing** (Stripe)
- **Required for demand signal APIs** (YouTube, Reddit)

### BR-3: Dockerfile produces a runnable container image

A multi-stage Dockerfile must build the application and produce a minimal container image that can be run with environment variables injected at runtime. The image must:
- Build the application from source (Gradle build)
- Use a minimal JRE base image for the runtime stage
- Expose the application port (8080)
- Accept all configuration via environment variables (no baked-in secrets)

### BR-4: `docker-compose.yml` updated for full-stack local deployment

The existing `docker-compose.yml` (currently PostgreSQL only) must be extended with:
- The application service, built from the Dockerfile
- Environment variable passthrough from `.env` file
- PostgreSQL dependency with health check
- Optional ngrok service for webhook tunneling (can be commented out or in a separate compose file)

### BR-5: Webhook tunnel configuration documented

Documentation must cover how to expose the local application to the internet for webhook delivery:
- ngrok setup and configuration
- Mapping the tunnel URL to Shopify and CJ webhook registration
- Verifying webhook delivery is working (checking app logs for incoming requests)

### BR-6: Shopify webhook registration documented

Step-by-step instructions for registering the `orders/create` webhook in the Shopify admin panel (or via Shopify Admin API), including:
- The webhook URL path (`/webhooks/shopify/orders`)
- The HMAC secret configuration
- How to verify the webhook is registered and delivering

### BR-7: CJ webhook registration documented

Step-by-step instructions for registering the tracking webhook in CJ's developer dashboard, including:
- The webhook URL path (`/webhooks/cj/tracking`)
- The authentication token configuration
- How to verify the webhook is registered and delivering

### BR-8: Supplier product mapping seeding documented

Instructions for creating the `supplier_product_mappings` database rows needed for CJ order placement, including:
- What the table schema looks like
- How to find the CJ product ID for a given product
- Example SQL insert statement
- How to verify the mapping is correct

### BR-9: End-to-end test runbook

A runbook document that walks an operator through executing the full order pipeline:

1. **Prerequisites**: env vars configured, app running, webhooks registered, tunnel active, supplier mapping seeded
2. **Place a test order**: Create a test order on the Shopify development store
3. **Verify order ingestion**: Confirm the webhook fires and the order appears in the database
4. **Verify CJ order placement**: Confirm the supplier order is placed via the CJ API
5. **Verify tracking webhook**: Wait for (or simulate) the CJ tracking webhook and confirm it is received and processed
6. **Verify Shopify fulfillment sync**: Confirm the fulfillment is synced back to Shopify
7. **Verify capital recording**: Confirm that capital records are created (or document why DELIVERED is unreachable and what the workaround is)
8. **Known gaps**: Explicitly document what cannot be tested end-to-end and why (e.g., DELIVERED status requires carrier tracking providers that do not exist yet)

### BR-10: Pipeline observability during E2E test

The operator must be able to observe the pipeline progressing through each step. This requires:
- Log output at INFO level for each pipeline step (most of this already exists)
- A way to query the database to see order status transitions
- Documentation of what to look for in logs at each step

## Success Criteria

### SC-1: App starts without `local` profile

`SPRING_PROFILES_ACTIVE=staging ./gradlew bootRun` (or equivalent Docker invocation) starts the application without bean creation errors. All Shopify and CJ real adapters are active. Carrier tracking providers are appropriately handled (stubbed or absent with graceful degradation).

### SC-2: `.env.example` is complete

Every environment variable referenced in `application.yml` and adapter `@Value` annotations is listed in `.env.example` with a descriptive comment. No operator following the example should encounter an "unexpected missing property" error.

### SC-3: Docker build succeeds

`docker build -t auto-shipper-ai .` produces a runnable image. `docker compose up` starts both PostgreSQL and the application, with the app successfully connecting to the database and running Flyway migrations.

### SC-4: Runbook is executable

An operator with valid API keys for Shopify (development store) and CJ can follow the runbook from start to finish. Each step has a verification check. The runbook explicitly documents where the pipeline succeeds, where it has known gaps, and what those gaps mean.

### SC-5: Webhook endpoints are reachable

With the tunnel active and webhooks registered, sending a test webhook (manually via curl or by triggering a real event) results in a 200 response from the application and a log entry confirming receipt.

### SC-6: Existing tests remain green

`./gradlew test` passes with zero failures. No existing test behavior is broken by profile changes, new configuration, or bean wiring modifications.

## Non-Functional Requirements

### NFR-1: No secrets in committed files

No API keys, access tokens, webhook secrets, or passwords may appear in any committed file (Dockerfile, docker-compose.yml, runbook, `.env.example`, application config). All secrets must be injected via environment variables at runtime. The `.env` file itself must be in `.gitignore`.

### NFR-2: Minimal image size

The Docker image must use a multi-stage build to avoid including the Gradle build toolchain, source code, or test resources in the final image. The runtime stage should use a JRE (not JDK) base image.

### NFR-3: Profile configuration is additive, not destructive

The new profile (if introduced) must not change the behavior of the existing `local` profile or the default (no profile) configuration. Existing `@Profile("local")` and `@Profile("!local")` annotations must not be modified unless strictly necessary to fix the startup failure.

### NFR-4: Runbook is self-contained

The runbook must not assume knowledge beyond what is documented in the project. An operator who has never worked with Shopify, CJ, or ngrok should be able to follow it. External links to official documentation are acceptable for account setup but the project-specific configuration steps must be fully documented.

### NFR-5: Graceful degradation for missing carrier tracking

The solution for the missing `CarrierTrackingProvider` beans must degrade gracefully. Options include:
- A `staging` profile that provides stub carrier tracking while using real Shopify/CJ adapters
- Making `ShipmentTracker` tolerant of an empty provider list
- Clearly documenting that the DELIVERED transition is not testable until real carrier tracking adapters are built

The chosen approach must not mask the gap -- it must be explicitly documented that carrier tracking is stubbed and why.

### NFR-6: Idempotent deployment

Running `docker compose up` multiple times must be safe. Flyway migrations handle database idempotency. The application must not fail on restart due to stale state.

## Dependencies

### External Dependencies

| Dependency | Risk | Mitigation |
|---|---|---|
| Shopify development store | Required for webhook registration and test orders | Free to create; no cost risk |
| CJ Dropshipping account | Required for order placement API and webhook registration | Account already exists (used for RAT-47 verification) |
| ngrok (or equivalent tunnel) | Required for webhook delivery to local machine | Free tier sufficient for testing; alternatives (localtunnel, Cloudflare Tunnel) documented as fallbacks |
| Docker | Required for containerized deployment | Standard tooling; already implied by existing `docker-compose.yml` |

### Internal Dependencies

| Dependency | Description |
|---|---|
| FR-028 (RAT-47) | CJ API contract reconciliation -- ensures CJ adapter code matches real API contracts. FR-029 tests the pipeline that FR-028 corrects. Ideally FR-028 completes first, but FR-029 can proceed in parallel (the E2E test will surface any remaining contract mismatches). |
| `StubCarrierTrackingConfiguration` | Current only source of `CarrierTrackingProvider` beans -- must be activated under the new profile or the dependency must be made optional. |
| `docker-compose.yml` | Existing file with PostgreSQL service -- must be extended, not replaced. |
| `.gitignore` | Must already contain `.env` (verify during implementation). |

### Constraint Dependencies

| CLAUDE.md Constraint | Relevance |
|---|---|
| #3 (No hardcoded rates) | Carrier rate APIs must use real credentials in non-local profiles; the staging profile must not hardcode rate values |
| #6 (Cross-module event listener pattern) | The E2E test validates that the `AFTER_COMMIT` + `REQUIRES_NEW` + `@Async` pattern works correctly across real transactions |
| #13 (Empty defaults on @Value) | Any new `@Value` annotations for staging-specific config must use `${key:}` syntax |
| #14 (No internal constructor on @Component) | Any new Spring components must not use Kotlin `internal` constructor |

## Out of Scope

The following are explicitly not part of this feature:

1. **Real carrier tracking provider implementations** -- Building UPS/FedEx/USPS tracking API integrations is a separate feature. This feature documents the gap and provides a graceful workaround.
2. **Production deployment** -- This feature targets a staging/development deployment for E2E testing, not a production-ready deployment with TLS, domain configuration, load balancing, or monitoring.
3. **CI/CD pipeline** -- Automated build, test, and deploy pipelines are a separate concern. This feature provides the Dockerfile and compose config as building blocks.
4. **Automated E2E test suite** -- The E2E test is a manual, operator-driven process following a runbook. Automated E2E testing (e.g., with Testcontainers or a test harness) is a separate feature.
5. **Frontend deployment** -- The React frontend is not part of the E2E order pipeline test. It may be containerized in a future feature.
6. **Multi-environment configuration** -- This feature introduces at most one new profile (`staging`). A full environment matrix (dev, staging, production) is a future concern.
7. **Shopify store setup** -- Creating a Shopify development store, installing the app, and configuring products is a prerequisite the operator must complete. The runbook documents what is needed but does not automate it.
8. **Cost optimization** -- Docker image size optimization beyond multi-stage builds, ngrok alternatives evaluation, and cloud deployment cost analysis are out of scope.
