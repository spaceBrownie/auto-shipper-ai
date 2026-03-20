# PM-013: Stub Adapter False Confidence — External API Contract Tests Missing System-Wide

**Date:** 2026-03-20
**Severity:** Medium
**Status:** Resolved (tracking issue created)
**Author:** Auto-generated from session

## Summary

During FR-020 (Platform Listing Adapter) implementation, the full SKU lifecycle E2E test passed — including platform listing creation, pause, and archive — but every Shopify API call hit `StubPlatformAdapter`, not the real Shopify Admin API. The unit tests mock `RestClient` at the method level, verifying internal logic (idempotency, state transitions, event chains) but not that the request body structure, response parsing, or authentication headers match what Shopify actually expects. This same gap exists for all 7 external API adapters in the system. A breaking change in any upstream API response shape would silently produce incorrect behavior in production with no test catching the regression.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-020 Phase 4 implementation began — PlatformAdapter interface, ShopifyListingAdapter, PlatformListingListener, entity, migration |
| ~15 min | Interface design required iteration: `pauseSku(PlatformListingId)` couldn't work because Shopify needs the external product ID, not our internal UUID. Redesigned to pass `externalListingId: String` |
| ~20 min | Kotlin smart-cast compilation error in `ShopifyPriceSyncAdapter` — cross-module property access can't be smart-cast. Fixed by extracting to local val |
| ~25 min | All 25 unit tests pass, `./gradlew test` green |
| ~30 min | E2E test: full lifecycle from SKU creation through LISTED → platform listing created (ACTIVE) → margin breach → PAUSED → listing set to DRAFT. All green |
| ~32 min | User question: "did we actually test the listing via Shopify?" — answer: no, everything hit StubPlatformAdapter |
| ~35 min | Discussion: unit tests with mocked RestClient verify internal logic but not API contract. Same gap exists for CJ, Google Trends, YouTube, Reddit adapters |
| ~40 min | Identified FR-017 (RAT-15) already spec'd for demand signal adapter WireMock tests. Created RAT-24 for Shopify adapter WireMock tests, blocked by RAT-15 |
| ~45 min | Reprioritized backlog: RAT-15 elevated to High, established execution order with contract tests first |

## Symptom

No runtime error or test failure — that's the problem. The system appeared to work correctly end-to-end:

```
PlatformListingListener -- Created platform listing for SKU f19d81ed... on SHOPIFY: externalId=381799c9...
StubPlatformAdapter     -- [STUB] Would pause Shopify product 381799c9...
PlatformListingListener -- Paused platform listing for SKU f19d81ed... on SHOPIFY
```

The `[STUB]` prefix is the only signal that no real Shopify call was made. The `platform_listings` table had correct data (ACTIVE → DRAFT status transition, correct price, deterministic stub UUIDs). Everything looked right.

## Root Cause

### 5 Whys

1. **Why** is there no confidence that Shopify API calls work? → All tests use either `StubPlatformAdapter` (E2E) or mocked `RestClient` (unit tests).

2. **Why** do unit tests mock `RestClient`? → Standard practice — unit tests shouldn't hit external services. But there's no intermediate layer of contract tests.

3. **Why** are there no contract tests? → The `@Profile("!local")` / `@Profile("local")` pattern was applied consistently for all external adapters, but the testing strategy stopped at "stub for local, real for production" without a middle ground.

4. **Why** wasn't the middle ground (WireMock) established earlier? → FR-017 (RAT-15) was spec'd for demand signal adapters on 2026-03-18 but put on hold for the demand signal API pivot (RAT-22). It was Medium priority.

5. **Why** was this gap not caught as a systemic risk? → Each adapter was built in isolation (FR-004, FR-006, FR-016, FR-020), and each followed the same pattern: real adapter + stub + unit tests with mocks. The pattern itself was the gap — it was consistently applied but consistently insufficient for API contract validation.

### The specific risks for ShopifyListingAdapter

The adapter constructs Shopify API requests using `Map` bodies serialized by Spring's `RestClient`:

```kotlin
val body = mapOf(
    "product" to mapOf(
        "title" to sku.sku.name,
        "product_type" to sku.sku.category,
        "status" to "active",
        "variants" to listOf(
            mapOf(
                "price" to price.normalizedAmount.toPlainString(),
                "sku" to sku.sku.skuId().toString(),
                "inventory_management" to "shopify"
            )
        )
    )
)
```

No test verifies this matches Shopify's actual expected request shape. Response parsing uses `objectMapper.readTree()` with `get()`:

```kotlin
val productId = product.get("id")?.asText()
val variantId = variants[0].get("id")?.asText()
```

No test verifies these field paths exist in a realistic Shopify response. If Shopify returns `"id": 7890123456` (numeric) instead of `"id": "7890123456"` (string), `asText()` would return `"7890123456"` — which happens to work. But if the response nests IDs differently or uses `"product_id"` vs `"id"`, the adapter would silently fail.

## Fix Applied

No code fix — the gap is structural. Created tracking issues to close it:

1. **RAT-15** (FR-017) — WireMock contract tests for demand signal adapters (CJ, Google Trends, YouTube, Reddit). Priority elevated from Medium to High. Establishes WireMock test infrastructure and fixture conventions.

2. **RAT-24** — WireMock contract tests for all 3 Shopify adapters (ShopifyListingAdapter, ShopifyPlatformFeeProvider, ShopifyPriceSyncAdapter). Blocked by RAT-15. Tests will validate request body structure, response parsing, auth headers, and error handling against realistic Shopify Admin API fixtures.

### Files Changed
- No code changes for this PM — tracking issues created in Linear

## Secondary Finding: Interface Design Iteration

During implementation, the `PlatformAdapter` interface required a redesign. The original plan defined:

```kotlin
fun pauseSku(listingId: PlatformListingId)    // internal UUID
fun archiveSku(listingId: PlatformListingId)  // internal UUID
```

But `ShopifyListingAdapter` needs the external Shopify product ID to make the PUT call. The adapter shouldn't inject `PlatformListingRepository` to resolve internal→external IDs — that couples the adapter to persistence.

The fix was to pass external IDs directly from the listener (which already has the entity):

```kotlin
fun pauseSku(externalListingId: String)
fun archiveSku(externalListingId: String)
fun updatePrice(externalVariantId: String, newPrice: Money)
```

This is a planning-phase gap: the implementation plan didn't trace the data flow from listener → adapter → HTTP call to verify the interface parameters were sufficient.

## Impact

- **No production impact** — FR-020 is not yet deployed
- **No data impact** — stub adapter is deterministic and side-effect-free
- **Developer confidence impact** — the E2E test creates an illusion of completeness that doesn't hold for real Shopify integration
- **Systemic scope** — all 7 external API adapters (CJ, Google Trends, YouTube, Reddit, Shopify fee, Shopify price sync, Shopify listing) share the same gap

## Lessons Learned

### What went well
- User caught the gap immediately by asking "did we actually test the listing via Shopify?" — healthy skepticism of green tests
- FR-017 spec already existed for demand signal adapters, so the pattern was identified; it just hadn't been prioritized
- The `[STUB]` log prefix makes it obvious in E2E output when stubs are active vs real adapters
- Creating RAT-24 with a `blockedBy` relationship to RAT-15 ensures the Shopify tests follow the same infrastructure

### What could be improved
- The `@Profile` adapter pattern should come with a documented expectation that WireMock contract tests are the third leg alongside real and stub implementations
- Implementation plans should trace data flow through interface boundaries to catch parameter mismatches (like PlatformListingId vs externalListingId) before coding starts
- New external API adapters should not be considered "complete" until contract tests exist — the definition of done for any adapter should include a WireMock test

## Prevention

- [ ] **Complete RAT-15 (FR-017)** — establish WireMock test infrastructure and fixture conventions with demand signal adapters
- [ ] **Complete RAT-24** — apply WireMock pattern to all Shopify adapters (listing, fee, price sync)
- [ ] **Add CLAUDE.md constraint #16** — "All `@Profile("!local")` external API adapters must have WireMock contract tests validating request structure and response parsing against realistic fixtures. Unit tests with mocked RestClient are necessary but not sufficient — they verify internal logic, not API contract compliance."
- [ ] **Update feature-request skill Phase 3 checklist** — implementation plans for adapters must include a "Contract Test" layer in the task breakdown, not just unit tests. This prevents the pattern of shipping adapters with only mock-level coverage.
- [ ] **Update feature-request skill Phase 4 definition of done** — an adapter implementation is not complete until contract tests exist. The phase 4 deliverable validation should check for WireMock test files when the changeset includes `@Profile("!local")` classes.
