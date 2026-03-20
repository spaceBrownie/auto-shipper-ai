# FR-020: Platform Listing Adapter

## Problem Statement

The system can discover demand signals, verify costs, stress-test margins, set prices, run compliance checks, and sync price updates to existing Shopify variants — but it cannot create a product listing on any platform. A SKU that passes every gate (compliance, cost verification, stress test) and transitions to `Listed` still requires manual product creation on Shopify. This was identified as Gap #2 in the executive readiness assessment (PM-007) and is the primary remaining obstacle to full operational autonomy.

The solo-operator spec (section 4.2) defines a `PlatformAdapter` interface with four operations (`listSku`, `pauseSku`, `updatePrice`, `getFees`), and section 6.2 specifies a `platform_listings` table — neither has been implemented. Meanwhile, the existing `ShopifyPriceSyncAdapter` in the pricing module and `ShopifyPlatformFeeProvider` in the catalog module handle price syncing and fee retrieval independently, creating fragmented Shopify integration with no unified adapter.

## Business Requirements

- When a SKU transitions to `Listed`, the system must automatically create a product listing on the target platform with no manual intervention
- When a SKU transitions to `Paused`, the system must set the platform listing to a draft/inactive state so it is no longer publicly visible
- When a SKU transitions to `Terminated`, the system must archive or remove the platform listing from the platform
- A `PlatformAdapter` interface must expose four operations: create a listing from a `LaunchReadySku`, pause a listing, update a listing's price, and retrieve platform fees for a product category and price point
- Each SKU-to-platform-listing mapping must be persisted in a `platform_listings` table to track which platform listing ID corresponds to which SKU, along with current price and listing status
- Listing creation must be idempotent: if a listing already exists for a given SKU on a given platform, the adapter must return the existing `PlatformListingId` rather than creating a duplicate
- Shopify is the Phase 1 target platform; Amazon, Etsy, and TikTok Shop are Phase 2+ targets, each requiring only a new adapter implementation against the same interface
- The existing `ShopifyPriceSyncAdapter` (pricing module) must be consolidated so that price syncing delegates to the `PlatformAdapter.updatePrice()` operation, eliminating duplicate Shopify integration paths
- The existing `ShopifyPlatformFeeProvider` (catalog module) should be evaluated for consolidation into `PlatformAdapter.getFees()` in a future iteration, but is not required for this feature since fee retrieval during cost verification operates at a different lifecycle stage than listing creation
- A stub adapter must be provided for local development, following the established `@Profile("local")` / `@Profile("!local")` pattern

## Success Criteria

- `PlatformListingId` value type exists in the shared module, following the `SkuId` / `OrderId` pattern
- `PlatformAdapter` interface exists in the catalog module with `listSku(LaunchReadySku): PlatformListingId`, `pauseSku(PlatformListingId)`, `updatePrice(PlatformListingId, Money)`, and `getFees(String, Money): PlatformFees` operations
- `ShopifyListingAdapter` implements `PlatformAdapter` using Shopify Admin API: creates products via POST, pauses via setting status to `draft`, archives on termination
- `PlatformListingEntity` JPA entity and corresponding Flyway migration (V19) persist listing records with columns: sku_id, platform, listing_id, current_price, status, created_at, updated_at
- `PlatformListingListener` reacts to `SkuStateChanged` events using the PM-005 double-annotation pattern (`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`) to trigger listing creation, pausing, or archival
- Idempotency: calling `listSku` for a SKU that already has a listing on the target platform returns the existing `PlatformListingId` without creating a duplicate
- `StubPlatformAdapter` returns deterministic `PlatformListingId` values and logs actions for local development
- `ShopifyPriceSyncAdapter.syncPrice()` delegates to `PlatformAdapter.updatePrice()` after looking up the `PlatformListingId` from the `platform_listings` table
- Unit tests cover: successful listing creation, idempotent re-listing, pause on PAUSED transition, archive on TERMINATED transition, price sync delegation, stub adapter behavior

## Non-Functional Requirements

- The `ShopifyListingAdapter` must use `@CircuitBreaker` and `@Retry` annotations consistent with existing Shopify adapters (`ShopifyPriceSyncAdapter`, `ShopifyPlatformFeeProvider`)
- All `@Value` annotations on adapter constructor parameters must use `${key:}` empty-default syntax (CLAUDE.md constraint #13) so beans can instantiate under any Spring profile
- The adapter must URL-encode any user-supplied values in request bodies (CLAUDE.md constraint #12)
- Listing operations must be logged at INFO level with SKU ID and platform listing ID for observability
- The `platform_listings` table must enforce a unique constraint on `(sku_id, platform)` to structurally prevent duplicate listings per platform
- Listing status in the `platform_listings` table must track the platform-side state (e.g., ACTIVE, DRAFT, ARCHIVED) independently from the SKU lifecycle state, since they may diverge during transient failures
- Transient Shopify API failures during listing creation must not leave the SKU in an inconsistent state — the SKU state transition to `Listed` has already committed before the listener fires (AFTER_COMMIT pattern), so a failed platform listing attempt must be retryable without re-triggering the state transition
- Platform adapter implementations must never use Kotlin `internal` constructors on `@Component` classes (CLAUDE.md constraint #14)

## Dependencies

- FR-001 (shared-domain-primitives) — `SkuId`, `Money`, `SkuStateChanged` event, value type patterns
- FR-003 (catalog-sku-lifecycle) — `Sku`, `SkuState`, `LaunchReadySku`, SKU state machine and transition events
- FR-005 (catalog-stress-test) — `StressTestedMargin`, `CostEnvelope.Verified` (components of `LaunchReadySku`)
- FR-006 (pricing-engine) — `ShopifyPriceSyncAdapter`, `PriceSyncAdapter` interface (consolidation target)
- FR-011 (compliance-guards) — compliance gate must have already cleared before a SKU reaches `Listed`; no direct code dependency but operational prerequisite
