# FR-020: Platform Listing Adapter — Implementation Plan

## Technical Design

### Architecture Overview

FR-020 bridges the last gap to full operational autonomy: when a SKU passes all gates (compliance, cost verification, stress test) and transitions to `Listed`, the system automatically creates a product on Shopify with no manual intervention. The design follows the established adapter pattern (`PlatformFeeProvider` / `PriceSyncAdapter`) but unifies all Shopify product-lifecycle operations behind a single `PlatformAdapter` interface.

```
StressTestService
       |
       | transitions SKU to LISTED
       v
  SkuService.transition()
       |
       | publishes SkuStateChanged event
       v
  PlatformListingListener           PricingInitializer
  (AFTER_COMMIT + REQUIRES_NEW)     (AFTER_COMMIT + REQUIRES_NEW)
       |                                    |
       | toState == LISTED                  | initializes price record
       | toState == PAUSED                  v
       | toState == TERMINATED         PricingDecisionListener
       v                                    |
  PlatformAdapter                           | Adjusted -> syncPrice()
       |                                    v
       |                            ShopifyPriceSyncAdapter
       |                                    |
       |                                    | delegates to
       |                                    v
       +-----> ShopifyListingAdapter (POST/PUT /admin/api/2024-01/products.json)
       |              |
       |              v
       |       PlatformListingRepository (platform_listings table)
       v
  StubPlatformAdapter (@Profile("local"))
```

### Data Flow

1. **Listing creation (LISTED transition):**
   `SkuStateChanged(toState=LISTED)` -> `PlatformListingListener` -> looks up `Sku` + `StressTestResultEntity` from DB -> constructs listing data -> `PlatformAdapter.listSku()` -> Shopify POST -> persists `PlatformListingEntity` with external IDs -> returns `PlatformListingId`

2. **Listing pause (PAUSED transition):**
   `SkuStateChanged(toState=PAUSED)` -> `PlatformListingListener` -> looks up `PlatformListingEntity` by skuId -> `PlatformAdapter.pauseSku()` -> Shopify PUT (status=draft) -> updates entity status to DRAFT

3. **Listing archive (TERMINATED transition):**
   `SkuStateChanged(toState=TERMINATED)` -> `PlatformListingListener` -> looks up `PlatformListingEntity` by skuId -> `PlatformAdapter.archiveSku()` -> Shopify PUT (status=archived) -> updates entity status to ARCHIVED

4. **Price sync delegation:**
   `PricingDecision.Adjusted` -> `PricingDecisionListener` -> `ShopifyPriceSyncAdapter.syncPrice()` -> looks up `PlatformListingEntity` by skuId -> reads `externalVariantId` -> `PlatformAdapter.updatePrice()` -> Shopify PUT variant price

### File Tree

```
modules/shared/src/main/kotlin/com/autoshipper/shared/identity/
  PlatformListingId.kt                          # NEW — @JvmInline value class

modules/catalog/src/main/kotlin/com/autoshipper/catalog/
  proxy/platform/
    PlatformAdapter.kt                          # NEW — interface (4 operations)
    ShopifyListingAdapter.kt                    # NEW — @Profile("!local"), Shopify Admin API
    StubPlatformAdapter.kt                      # NEW — @Profile("local"), deterministic stubs
    PlatformFees.kt                             # NEW — data class for getFees() return type
  persistence/
    PlatformListingEntity.kt                    # NEW — JPA entity for platform_listings table
    PlatformListingRepository.kt                # NEW — Spring Data JPA repository
  domain/service/
    PlatformListingListener.kt                  # NEW — @TransactionalEventListener

modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/
  ShopifyPriceSyncAdapter.kt                    # MODIFIED — delegates to PlatformAdapter.updatePrice()

modules/app/src/main/resources/
  db/migration/V19__platform_listings.sql       # NEW — DDL for platform_listings table
  application.yml                               # MODIFIED — resilience4j instances for shopify-listing
```

---

## Architecture Decisions

### AD-1: PlatformAdapter interface lives in the catalog module

**Decision:** Define `PlatformAdapter` in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/`.

**Rationale:** The catalog module already owns the SKU lifecycle, `LaunchReadySku`, and the existing `PlatformFeeProvider`. The pricing module already depends on catalog (`implementation(project(":catalog"))` in its `build.gradle.kts`), so it can access `PlatformAdapter` without introducing a new dependency edge. Placing the interface in `shared` would pull domain types (`LaunchReadySku`, `PlatformFees`) into the shared module, violating the bounded-context principle.

### AD-2: ShopifyPriceSyncAdapter delegates to PlatformAdapter.updatePrice() via PlatformListingRepository

**Decision:** Modify `ShopifyPriceSyncAdapter` to inject `PlatformAdapter` and `PlatformListingRepository`. On `syncPrice(skuId, newPrice)`, it looks up the `PlatformListingEntity` by `skuId` to get the `PlatformListingId`, then delegates to `PlatformAdapter.updatePrice(listingId, newPrice)`. If no listing exists yet (SKU listed before FR-020 was deployed), it falls back to the current direct Shopify variant PUT using `skuId` as variant ID.

**Rationale:** This consolidates all Shopify write operations through a single adapter while maintaining backward compatibility. The fallback path ensures existing listed SKUs (which lack `platform_listings` records) continue to work. The `PriceSyncAdapter` interface is preserved — `ShopifyPriceSyncAdapter` still implements it — so `PricingDecisionListener` requires zero changes.

### AD-3: PlatformListingListener reconstructs listing data from DB

**Decision:** `PlatformListingListener` receives `SkuStateChanged` (which carries only `skuId`, `fromState`, `toState`). For LISTED transitions, it loads `Sku` from `SkuRepository`, `StressTestResultEntity` from `StressTestResultRepository` (latest by `testedAt`), and price from `SkuPriceRepository` (once PricingInitializer has run). It constructs a `LaunchReadySku` to pass to `PlatformAdapter.listSku()`.

**Rationale:** This follows the `PricingInitializer` pattern exactly — both listeners react to the same `SkuStateChanged` event and reconstruct domain data from DB. `SkuStateChanged` is intentionally thin (no payload bloat). Because `PlatformListingListener` runs in a new transaction (`REQUIRES_NEW`) after the original commit, all data written by `StressTestService` and the SKU state transition is guaranteed visible.

**Note:** Both `PlatformListingListener` and `PricingInitializer` fire on the same LISTED transition. Since both use `AFTER_COMMIT` + `REQUIRES_NEW`, they execute in separate transactions. `PlatformListingListener` does not depend on the price record created by `PricingInitializer` — Shopify product creation uses the `estimatedPriceAmount` from `StressTestResultEntity`, not the pricing engine's initial price. The pricing engine may later adjust the price, which flows through `PricingDecisionListener` -> `PriceSyncAdapter` -> `PlatformAdapter.updatePrice()`.

### AD-4: PlatformListingEntity schema

**Decision:** The `platform_listings` table has these columns:

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Internal record ID |
| `sku_id` | UUID NOT NULL | FK to `skus(id)` |
| `platform` | VARCHAR(50) NOT NULL | e.g., "SHOPIFY" |
| `external_listing_id` | VARCHAR(255) NOT NULL | Shopify product ID |
| `external_variant_id` | VARCHAR(255) | Shopify variant ID (needed for price updates) |
| `current_price_amount` | NUMERIC(19,4) NOT NULL | Last synced price |
| `currency` | VARCHAR(3) NOT NULL | ISO currency code |
| `status` | VARCHAR(30) NOT NULL | ACTIVE, DRAFT, ARCHIVED |
| `created_at` | TIMESTAMP NOT NULL | Record creation time |
| `updated_at` | TIMESTAMP NOT NULL | Last modification time |

Unique constraint: `(sku_id, platform)` — structurally prevents duplicate listings per platform.

**Rationale:** `external_variant_id` is critical for price sync — Shopify's variant API (`PUT /variants/{id}.json`) requires the variant ID, not the product ID. Storing it at listing creation time avoids a round-trip to Shopify on every price update. The unique constraint on `(sku_id, platform)` enables the idempotency guard: `listSku` checks for existing record before calling Shopify.

### AD-5: Listing status tracked independently from SKU state

**Decision:** `PlatformListingEntity.status` is one of `ACTIVE`, `DRAFT`, `ARCHIVED` — these are platform-side states, not SKU lifecycle states.

**Rationale:** A SKU may be in `Listed` state while its Shopify listing is in `DRAFT` due to a transient API failure during activation. Decoupling these states allows retry logic to detect and recover from partial failures. The listener updates the entity status only after a successful platform API call.

### AD-6: No changes to StressTestService or the state machine

**Decision:** `StressTestService` already transitions passing SKUs to `Listed` via `SkuService.transition()`, which publishes `SkuStateChanged`. No modifications needed.

**Rationale:** The event-driven architecture means new listeners (like `PlatformListingListener`) can react to existing events without modifying producers. This is the same pattern used by `PricingInitializer`.

### AD-7: ShopifyPlatformFeeProvider NOT consolidated in this FR

**Decision:** `ShopifyPlatformFeeProvider` remains unchanged. `PlatformAdapter` defines `getFees()` but `ShopifyListingAdapter`'s implementation simply delegates to the existing `ShopifyPlatformFeeProvider` internally for now.

**Rationale:** Fee retrieval operates during cost verification (well before listing creation). The `PlatformFeeProvider` interface is consumed by `CostGateService` and has different retry/circuit-breaker characteristics. Full consolidation is a future optimization that doesn't block listing creation.

### AD-8: ShopifyListingAdapter uses @Value with empty defaults

**Decision:** All `@Value` parameters use `${key:}` syntax: `@Value("\${shopify.api.access-token:}")`.

**Rationale:** CLAUDE.md constraint #13. Spring resolves `@Value` during constructor injection before evaluating `@ConditionalOnProperty` or `@Profile`. Missing properties crash even disabled beans. The adapter's `listSku()` method guards against blank values with an early-return log warning.

---

## Layer-by-Layer Implementation

### Layer 1: Shared Module (Identity Type)

**File: `PlatformListingId.kt`**

Create `@JvmInline value class PlatformListingId(val value: UUID)` following the exact pattern of `SkuId` and `OrderId`:
- `companion object` with `fun new()` and `fun of(value: String)`
- `override fun toString()`

### Layer 2: Catalog Module — Persistence

**File: `V19__platform_listings.sql`**

```sql
CREATE TABLE platform_listings (
    id                   UUID PRIMARY KEY,
    sku_id               UUID NOT NULL REFERENCES skus(id),
    platform             VARCHAR(50)  NOT NULL,
    external_listing_id  VARCHAR(255) NOT NULL,
    external_variant_id  VARCHAR(255),
    current_price_amount NUMERIC(19,4) NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_platform_listings_sku_platform
    ON platform_listings(sku_id, platform);

CREATE INDEX idx_platform_listings_sku_id
    ON platform_listings(sku_id);

CREATE INDEX idx_platform_listings_status
    ON platform_listings(status);
```

**File: `PlatformListingEntity.kt`**

JPA entity mapping to `platform_listings`. Follows `CostEnvelopeEntity` / `StressTestResultEntity` patterns:
- UUID PK with default `UUID.randomUUID()`
- `var status: String` and `var updatedAt: Instant` are mutable (updated on pause/archive)
- All other fields are `val` (immutable after creation)
- No `@JdbcTypeCode` needed — no JSONB columns

**File: `PlatformListingRepository.kt`**

Spring Data JPA interface:
- `fun findBySkuId(skuId: UUID): PlatformListingEntity?`
- `fun findBySkuIdAndPlatform(skuId: UUID, platform: String): PlatformListingEntity?`

### Layer 3: Catalog Module — Adapter Interface and Implementations

**File: `PlatformFees.kt`**

```kotlin
data class PlatformFees(
    val transactionFee: Money,
    val listingFee: Money
)
```

**File: `PlatformAdapter.kt`**

```kotlin
interface PlatformAdapter {
    fun listSku(sku: LaunchReadySku, price: Money): PlatformListingId
    fun pauseSku(listingId: PlatformListingId)
    fun archiveSku(listingId: PlatformListingId)
    fun updatePrice(listingId: PlatformListingId, variantId: String, newPrice: Money)
    fun getFees(productCategory: String, price: Money): PlatformFees
}
```

Note: `listSku` takes `price: Money` as an additional parameter because the listener reads the estimated price from `StressTestResultEntity`. `archiveSku` is separated from `pauseSku` because Shopify uses different status values (`draft` vs `archived`), and the business semantics differ (pause is reversible, archive is not). `updatePrice` takes `variantId: String` because the variant ID is stored in `PlatformListingEntity` and looked up by the caller.

**File: `ShopifyListingAdapter.kt`**

- `@Component @Profile("!local")`
- Constructor injects `@Qualifier("shopifyRestClient") RestClient` and `@Value("\${shopify.api.access-token:}") accessToken`
- `@CircuitBreaker(name = "shopify-listing") @Retry(name = "shopify-listing")` on all methods
- `listSku()`: POST `/admin/api/2024-01/products.json` with product title (SKU name), category, variant with price. Parses response to extract product ID and variant ID. Returns `PlatformListingId`.
- `pauseSku()`: PUT `/admin/api/2024-01/products/{id}.json` with `{"product": {"status": "draft"}}`
- `archiveSku()`: PUT `/admin/api/2024-01/products/{id}.json` with `{"product": {"status": "archived"}}`
- `updatePrice()`: PUT `/admin/api/2024-01/variants/{variantId}.json` with new price
- `getFees()`: Delegates to internal logic (same as `ShopifyPlatformFeeProvider`)
- URL-encodes any user-supplied values in request bodies (CLAUDE.md #12)
- Uses Jackson `get()` not `path()` for response parsing (CLAUDE.md #15)
- Guards against blank `accessToken` with early-return + log warning (CLAUDE.md #13)
- No `internal` constructor (CLAUDE.md #14)

**File: `StubPlatformAdapter.kt`**

- `@Component @Profile("local")`
- Returns deterministic `PlatformListingId` (UUID v5 from SKU name or a fixed seed)
- Logs all operations at INFO with `[STUB]` prefix
- Follows `StubPriceSyncAdapter` / `StubPlatformFeeProvider` patterns

### Layer 4: Catalog Module — Event Listener

**File: `PlatformListingListener.kt`**

- `@Component`
- Constructor injects: `PlatformAdapter`, `PlatformListingRepository`, `SkuRepository`, `StressTestResultRepository`
- `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` + `@Transactional(propagation = Propagation.REQUIRES_NEW)` (CLAUDE.md #6)
- `fun onSkuStateChanged(event: SkuStateChanged)`:
  - `LISTED` -> calls `handleListed(skuId)`
  - `PAUSED` -> calls `handlePaused(skuId)`
  - `TERMINATED` -> calls `handleTerminated(skuId)`
  - All other transitions: no-op return

**handleListed(skuId):**
1. Idempotency guard: check `PlatformListingRepository.findBySkuIdAndPlatform(skuId, "SHOPIFY")`. If exists, log and return existing ID.
2. Load `Sku` from `SkuRepository`
3. Load latest `StressTestResultEntity` from `StressTestResultRepository`
4. Load `CostEnvelopeEntity` from `CostEnvelopeRepository`
5. Reconstruct `CostEnvelope.Verified` and `StressTestedMargin` -> build `LaunchReadySku`
6. Read estimated price from `StressTestResultEntity.estimatedPriceAmount`
7. Call `PlatformAdapter.listSku(launchReadySku, price)` -> get `PlatformListingId`
8. Persist `PlatformListingEntity` with external IDs, price, status=ACTIVE

**Note on LaunchReadySku reconstruction:** `CostEnvelope.Verified` has an `internal constructor`, but `PlatformListingListener` lives in the catalog module, so it has visibility. This is the correct bounded-context pattern documented in MEMORY.md.

**handlePaused(skuId):**
1. Look up `PlatformListingEntity` by skuId. If none exists, log warning and return (SKU was listed before FR-020).
2. Call `PlatformAdapter.pauseSku(listingId)`
3. Update entity status to `DRAFT`, update `updatedAt`

**handleTerminated(skuId):**
1. Look up `PlatformListingEntity` by skuId. If none exists, log warning and return.
2. Call `PlatformAdapter.archiveSku(listingId)`
3. Update entity status to `ARCHIVED`, update `updatedAt`

### Layer 5: Pricing Module — Consolidation

**File: `ShopifyPriceSyncAdapter.kt` (MODIFIED)**

- Add constructor parameters: `PlatformAdapter` and `PlatformListingRepository`
- In `syncPrice(skuId, newPrice)`:
  1. Look up `PlatformListingEntity` by `skuId.value`
  2. If found: delegate to `PlatformAdapter.updatePrice(listingId, variantId, newPrice)`, then update `current_price_amount` and `updated_at` on the entity
  3. If not found (backward compat): fall back to current direct Shopify PUT using `skuId.value` as variant ID
- `StubPriceSyncAdapter` is unchanged (it doesn't call Shopify)

### Layer 6: Configuration

**File: `application.yml` (MODIFIED)**

Add resilience4j circuit breaker and retry instances for `shopify-listing`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      shopify-listing:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
  retry:
    instances:
      shopify-listing:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
```

Note: listing creation uses a slightly longer base wait (1s vs 500ms for price sync) because product creation is a heavier operation and Shopify may rate-limit more aggressively.

---

## Task Breakdown

### Layer 1: Shared Module
- [x] Create `PlatformListingId` value class in `modules/shared/src/main/kotlin/com/autoshipper/shared/identity/PlatformListingId.kt`

### Layer 2: Persistence
- [x] Create `V19__platform_listings.sql` Flyway migration in `modules/app/src/main/resources/db/migration/`
- [x] Create `PlatformListingEntity` JPA entity in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/persistence/PlatformListingEntity.kt`
- [x] Create `PlatformListingRepository` Spring Data interface in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/persistence/PlatformListingRepository.kt`

### Layer 3: Adapter Interface and Implementations
- [x] Create `PlatformFees` data class in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformFees.kt`
- [x] Create `PlatformAdapter` interface in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformAdapter.kt`
- [x] Create `ShopifyListingAdapter` in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyListingAdapter.kt`
- [x] Create `StubPlatformAdapter` in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/StubPlatformAdapter.kt`

### Layer 4: Event Listener
- [x] Create `PlatformListingListener` in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/PlatformListingListener.kt`

### Layer 5: Pricing Module Consolidation
- [x] Modify `ShopifyPriceSyncAdapter` to delegate price updates to `PlatformAdapter.updatePrice()` with backward-compat fallback

### Layer 6: Configuration
- [x] Add `shopify-listing` circuit breaker and retry config to `application.yml`

### Layer 7: Tests
- [x] Unit test: `ShopifyListingAdapterTest` — product creation POST, pause PUT, archive PUT, price update PUT, response parsing with Jackson `get()`
- [x] Unit test: `StubPlatformAdapterTest` — deterministic IDs, logging verification
- [x] Unit test: `PlatformListingListenerTest` — LISTED creates listing (idempotent), PAUSED sets DRAFT, TERMINATED sets ARCHIVED, unknown transition is no-op, missing stress test logs warning
- [x] Unit test: `ShopifyPriceSyncAdapterTest` — delegates to PlatformAdapter when listing exists, falls back to direct PUT when no listing
- [x] Unit test: `PlatformListingEntityTest` — covered by PlatformListingListenerTest (entity creation, status updates via listener CRUD flows)

---

## Testing Strategy

### Unit Tests

All unit tests use Mockito (via `mockito-kotlin`) following established patterns:

1. **ShopifyListingAdapterTest:**
   - Mock `RestClient` and its fluent builder chain
   - Verify POST body structure for product creation (title, status=active, variant with price)
   - Verify PUT body for pause (status=draft) and archive (status=archived)
   - Verify response parsing uses `get()` not `path()` (CLAUDE.md #15)
   - Verify URL encoding of SKU name in product title (CLAUDE.md #12)
   - Verify early return when accessToken is blank (CLAUDE.md #13)
   - Assert exact `PlatformListingId` extracted from mock Shopify response

2. **PlatformListingListenerTest:**
   - **Happy path (LISTED):** mock repos return valid Sku, StressTestResultEntity, CostEnvelopeEntity; verify `PlatformAdapter.listSku()` called; verify `PlatformListingEntity` saved with correct fields
   - **Idempotency:** when `PlatformListingRepository.findBySkuIdAndPlatform()` returns existing entity, verify `PlatformAdapter.listSku()` NOT called
   - **PAUSED:** verify `PlatformAdapter.pauseSku()` called and entity status updated to DRAFT
   - **TERMINATED:** verify `PlatformAdapter.archiveSku()` called and entity status updated to ARCHIVED
   - **No listing exists for pause/terminate:** verify warning logged, no adapter call, no exception
   - **Missing stress test result:** verify warning logged, no adapter call

3. **ShopifyPriceSyncAdapter (modified) test:**
   - When `PlatformListingEntity` exists: verify delegates to `PlatformAdapter.updatePrice()` with correct variant ID
   - When no `PlatformListingEntity`: verify falls back to direct Shopify PUT (backward compat)
   - Verify entity `current_price_amount` and `updated_at` updated after successful delegation

4. **StubPlatformAdapterTest:**
   - Verify deterministic `PlatformListingId` returned
   - Verify no external calls made

### Financial Value Assertions

Per testing conventions: all monetary values assert exact `Money` amounts — no `any<Money>()` matchers in financial operations.

### Failure-Path Tests

Per testing conventions: the listener must have failure-path tests verifying that when `PlatformAdapter.listSku()` throws, the `PlatformListingEntity` is NOT persisted (the entire `REQUIRES_NEW` transaction rolls back), while the SKU remains in `Listed` state (the original transaction already committed).

---

## Rollout Plan

### Phase 1: Local Development (this FR)

1. Deploy with `@Profile("local")` active — `StubPlatformAdapter` handles all calls
2. Verify via logs: `[STUB] Would create listing for SKU ...`
3. Run `./gradlew test` — all unit tests pass
4. Run `./gradlew flywayMigrate` against local DB — V19 migration applies cleanly
5. Verify `platform_listings` table created with correct schema and constraints

### Phase 2: Staging (post-merge)

1. Set `SHOPIFY_ACCESS_TOKEN` and `SHOPIFY_API_BASE_URL` environment variables
2. Deploy with `!local` profile active
3. Manually trigger a stress test pass on a test SKU
4. Verify Shopify product created via Shopify Admin dashboard
5. Verify `platform_listings` row with correct external IDs
6. Trigger pause -> verify Shopify product status changed to draft
7. Trigger terminate -> verify Shopify product status changed to archived

### Phase 3: Production

1. Deploy behind feature flag (optional: `platform-listing.enabled=true` config property)
2. Monitor circuit breaker metrics: `resilience4j.circuitbreaker.shopify-listing.*`
3. Monitor listing creation latency and error rate
4. Verify price sync consolidation works for newly listed SKUs
5. Verify backward-compat price sync works for SKUs listed before FR-020

### Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Shopify rate limiting on product creation | Circuit breaker + exponential backoff retry; listing creation is infrequent (only on new SKU launch) |
| PlatformListingListener and PricingInitializer ordering | Both are independent AFTER_COMMIT listeners; no ordering dependency. Listing uses `estimatedPriceAmount` from stress test, not the pricing engine. |
| Existing SKUs without platform_listings records | Backward-compat fallback in ShopifyPriceSyncAdapter; no migration needed for existing data |
| LaunchReadySku reconstruction from DB may fail if data is inconsistent | Same risk as PricingInitializer; both log warnings and return early on missing data |
