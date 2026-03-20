# FR-020: Platform Listing Adapter — Implementation Summary

## Feature Summary

Implemented the `PlatformAdapter` interface and Shopify integration that automatically creates, pauses, and archives product listings on Shopify when SKUs transition through lifecycle states. This bridges the last gap to full operational autonomy — a SKU that passes all gates (compliance, cost verification, stress test) now gets listed on Shopify with zero manual intervention.

## Changes Made

### New Files (11)

**Shared Module:**
- `PlatformListingId.kt` — `@JvmInline value class` following `SkuId`/`OrderId` pattern

**Catalog Module — Persistence:**
- `V19__platform_listings.sql` — Flyway migration with unique constraint on `(sku_id, platform)`, indexes on `sku_id` and `status`
- `PlatformListingEntity.kt` — JPA entity tracking SKU-to-platform-listing mappings with external IDs, price, and platform-side status
- `PlatformListingRepository.kt` — Spring Data JPA with `findBySkuId` and `findBySkuIdAndPlatform` queries

**Catalog Module — Adapters:**
- `PlatformAdapter.kt` — Interface with 5 operations: `listSku`, `pauseSku`, `archiveSku`, `updatePrice`, `getFees`; plus `PlatformListingResult` return type
- `PlatformFees.kt` — Data class for `getFees()` return type
- `ShopifyListingAdapter.kt` — `@Profile("!local")` implementation using Shopify Admin API with `@CircuitBreaker` + `@Retry`, `@Value` empty defaults (CLAUDE.md #13), Jackson `get()` (CLAUDE.md #15)
- `StubPlatformAdapter.kt` — `@Profile("local")` stub with deterministic UUID v5 external IDs and `[STUB]` logging

**Catalog Module — Event Listener:**
- `PlatformListingListener.kt` — Reacts to `SkuStateChanged` using PM-005 double-annotation pattern (`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`). Handles LISTED (create), PAUSED (draft), TERMINATED (archive) with idempotency guard.

**Tests (4 files, 25 tests):**
- `ShopifyListingAdapterTest.kt` — 7 tests: product creation, blank token guard, missing response, pause/archive PUT bodies, price update, getFees
- `StubPlatformAdapterTest.kt` — 5 tests: deterministic IDs, no-throw operations, fee calculation
- `PlatformListingListenerTest.kt` — 10 tests: happy path, idempotency, PAUSED→DRAFT, TERMINATED→ARCHIVED, missing data guards, adapter failure rollback, annotation verification
- `ShopifyPriceSyncAdapterTest.kt` — 3 tests: PlatformAdapter delegation, direct fallback, null variant ID fallback

## Files Modified

### Modified Files (2)

- `ShopifyPriceSyncAdapter.kt` — Now injects `PlatformAdapter` + `PlatformListingRepository`; delegates to `PlatformAdapter.updatePrice()` when a listing exists, falls back to direct Shopify PUT for backward compatibility
- `application.yml` — Added `shopify-listing` circuit breaker (window=10, threshold=50%, wait=30s) and retry (3 attempts, 1s base, exponential backoff)

## Design Deviation from Plan

The `PlatformAdapter` interface was adjusted to pass external platform IDs (strings) to `pauseSku`, `archiveSku`, and `updatePrice` instead of `PlatformListingId` (our internal UUID). This avoids injecting the repository into the adapter — the listener resolves external IDs from the entity and passes them directly. Cleaner separation of concerns.

## Testing Completed

- `./gradlew test` — **BUILD SUCCESSFUL** — all 25 new tests pass alongside existing test suite
- All CLAUDE.md constraints verified:
  - #6: Double annotation pattern on listener (tested via reflection)
  - #12: URL encoding not needed (product data sent via JSON body, not form-encoded)
  - #13: `@Value` empty defaults on ShopifyListingAdapter
  - #14: No `internal` constructors on `@Component` classes
  - #15: Jackson `get()` used for response parsing

## Deployment Notes

- **V19 migration** creates the `platform_listings` table — run `./gradlew flywayMigrate` before deploying
- **Local profile**: `StubPlatformAdapter` handles all calls; no Shopify credentials needed
- **Production**: Set `SHOPIFY_ACCESS_TOKEN` and `SHOPIFY_API_BASE_URL` environment variables
- **Backward compatibility**: Existing listed SKUs (without `platform_listings` records) continue to work via the direct Shopify PUT fallback in `ShopifyPriceSyncAdapter`
- **Monitoring**: Watch `resilience4j.circuitbreaker.shopify-listing.*` metrics
