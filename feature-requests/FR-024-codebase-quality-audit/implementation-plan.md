# FR-024: Codebase Quality Audit — Implementation Plan

## Technical Design

### Architecture Overview

This is a one-time compliance audit, not a new feature. Changes are scoped to annotation corrections, URL-encoding additions, logging additions, an ArchUnit rule addition, a CLAUDE.md documentation update, and a mockk investigation document. No new modules, no database migrations, no new domain logic.

**Modules touched:**

| Module | Files Changed | BRs |
|---|---|---|
| `fulfillment` | `VendorSlaBreachRefunder.kt`, `StripeRefundAdapter.kt` | BR-1, BR-2 |
| `catalog` | `ExternalApiConfig.kt`, `UpsRateAdapter.kt`, `FedExRateAdapter.kt`, `UspsRateAdapter.kt`, `StripeProcessingFeeProvider.kt` | BR-2, BR-3 |
| `portfolio` | `AmazonCreatorsApiAdapter.kt` | BR-3 |
| `pricing` | `PricingInitializer.kt` | BR-5 |
| `app` | `ArchitectureTest.kt` | BR-4 |
| (root) | `CLAUDE.md` | BR-7 |
| (feature-request dir) | `mockk-investigation.md` | BR-6 |

### BR-1: VendorSlaBreachRefunder Transaction Boundary Fix

**Current state:** `VendorSlaBreachRefunder` in the fulfillment module uses `@EventListener` + `@Transactional` to handle `VendorSlaBreached` (published by vendor module's `VendorSlaMonitor`). This is a cross-module listener that writes to the database (issues refunds via `RefundProvider`, updates order status to `REFUNDED`, persists via `OrderRepository.save()`).

**Problem:** With `@EventListener`, the handler runs inside the vendor module's publishing transaction. If that transaction rolls back after the fulfillment handler has already called `refundProvider.refund()` (an external Stripe API call), the refunds are issued but the order status updates are rolled back — producing phantom refunds with no database record.

**Fix:** Replace annotations:
- `@EventListener` -> `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
- `@Transactional` -> `@Transactional(propagation = Propagation.REQUIRES_NEW)`

This ensures: (1) the handler only fires after the vendor module's transaction has committed (no phantom trigger on rollback), and (2) the handler's own writes occur in a new transaction that commits independently.

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/VendorSlaBreachRefunder.kt`

### BR-2: @Value Empty Defaults

**Current state:** 12 `@Value` annotations across 6 files lack the `${key:}` empty-default syntax. The affected beans are gated by `@Profile("!local")` or `@ConditionalOnProperty`, but Spring resolves `@Value` during constructor injection *before* evaluating these conditions. If a property is missing from the active profile, the application context crashes.

**Fix per file:**

1. **`ExternalApiConfig`** (5 properties): Add `:}` to each `@Value` annotation. Since this is a `@Configuration` class creating `RestClient` beans, add a guard that logs a warning and returns a no-op `RestClient` when the URL is blank.

2. **`UpsRateAdapter`** (2 properties): Add `:}` to `client-id` and `client-secret`. Add early-return guard at the top of `getRate()` that checks for blank values, logs a warning, and throws `ProviderUnavailableException`.

3. **`FedExRateAdapter`** (2 properties): Same pattern as UPS.

4. **`UspsRateAdapter`** (1 property): Add `:}` to `oauth-token`. Add early-return guard in `getRate()`.

5. **`StripeProcessingFeeProvider`** (1 property): Add `:}` to `secret-key`. Add early-return guard in `getFee()`.

6. **`StripeRefundAdapter`** (1 property): Add `:}` to `secret-key`. Add early-return guard in `refund()`.

**Guard pattern:** Each adapter checks `if (relevantField.isBlank())` at the entry point. For carrier adapters and Stripe fee provider, throw `ProviderUnavailableException("CarrierName/Stripe", IllegalStateException("credentials not configured"))` so the caller's existing error handling works. For StripeRefundAdapter, throw `IllegalStateException` since it has no `ProviderUnavailableException` in scope.

### BR-3: URL-Encode OAuth Token Request Bodies

**Current state:**

1. **`FedExRateAdapter.fetchBearerToken()`** (line 53): Constructs form body as:
   ```
   "grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret"
   ```
   If `clientId` or `clientSecret` contains `&`, `=`, or `+`, the form body is corrupted.

2. **`AmazonCreatorsApiAdapter.getAccessToken()`** (line 107): Constructs form body as:
   ```
   "grant_type=client_credentials&client_id=$credentialId&client_secret=$credentialSecret"
   ```
   Same vulnerability.

**Fix:** Wrap each user-supplied value with `URLEncoder.encode(value, StandardCharsets.UTF_8)`:
```kotlin
val body = "grant_type=client_credentials" +
    "&client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
    "&client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}"
```

Add `import java.net.URLEncoder` and `import java.nio.charset.StandardCharsets` where not already present.

**Note:** `StripeRefundAdapter.refund()` already uses `URLEncoder.encode()` for `paymentIntentId` and `orderId` — it was fixed in an earlier pass. No change needed there.

### BR-4: ArchUnit Rule 4 — Cross-Module @EventListener Detection

**Current state:** `ArchitectureTest` has 3 rules:
1. `AFTER_COMMIT` listeners must use `REQUIRES_NEW` transaction
2. No `@Testcontainers` in test code
3. `@Entity` with assigned `@Id` must implement `Persistable`

None of these catch the `VendorSlaBreachRefunder` pattern: a plain `@EventListener` on a method whose parameter type is defined in a different module (via `shared` events).

**Design:** The new Rule 4 detects `@EventListener`-annotated methods where:
1. The handler class package belongs to module X (e.g., `com.autoshipper.fulfillment`)
2. The event parameter type is a domain event from the shared module (`com.autoshipper.shared.events`)
3. The event is known to be published by a different module

Since all domain events live in `shared`, we cannot determine the publisher from the event type alone. The pragmatic approach: flag any `@EventListener` method in a non-shared module that handles an event type from `com.autoshipper.shared.events` that is NOT the module's own event type. We define "own event type" by a naming convention or a hardcoded allowlist.

**Simpler approach:** Since CLAUDE.md constraint #6 says "plain `@EventListener` is acceptable only for same-module listeners that should rollback with the publisher," and all cross-module events go through `shared`, the rule should flag any `@EventListener` method whose event parameter type lives in `com.autoshipper.shared.events`. This is conservative: it requires all listeners of shared events to use `@TransactionalEventListener`. If a same-module listener truly needs `@EventListener` semantics for a shared event type, it can be exempted via annotation or the rule can be refined.

**Exception:** `PricingEngine.onPricingSignal()` currently uses `@EventListener` and handles `PricingSignal` (a shared event). It is a cross-module listener (capital publishes, pricing consumes) that writes to the database. By the strictest reading of constraint #6, it should also be `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`. However, `PricingEngine` is already `@Transactional` at the class level and the `@EventListener` runs inside the publisher's transaction. Changing it to `@TransactionalEventListener` is a separate concern with its own risk profile. For this audit, the ArchUnit rule will flag it as a known violation, and we will suppress it with a `// ArchUnit: suppressed — see FR-024 BR-4 note` comment and a `@SuppressWarnings` approach in the rule's allowlist. The PricingEngine transaction boundary is tracked as a future improvement.

**File:** `modules/app/src/test/kotlin/com/autoshipper/ArchitectureTest.kt`

### BR-5: PricingInitializer Post-Persist Verification Log

**Current state:** `PricingInitializer.onSkuStateChanged()` calls `pricingEngine.setInitialPrice()` which persists `SkuPriceEntity` and `SkuPricingHistoryEntity`. The existing log on line 68 says "Initialized pricing for SKU..." but this fires *before* the transaction commits. If the `REQUIRES_NEW` transaction silently fails to commit (e.g., constraint violation), the log message would be misleading.

**Fix:** Add a post-persist verification: after calling `setInitialPrice()`, re-read from `skuPriceRepository.findBySkuId()` and log the persisted entity's values. This confirms the write actually hit the database within the same `REQUIRES_NEW` transaction. Log at INFO level with SKU ID, price, and margin.

**File:** `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt`

### BR-6: mockk Investigation Document

**Scope:** Read-only investigation. No code changes. Output is a markdown document in the feature request directory.

**Investigation plan:**
1. Document the specific Mockito limitation with Kotlin inline value classes (`SkuId`)
2. Reproduce the issue conceptually (Mockito's `any<SkuId>()` returns null for inline/value classes)
3. Research whether mockk's `every { }` / `verify { }` can handle value classes
4. Estimate migration effort (268 Mockito imports across 52 test files)
5. Provide a clear recommend/defer/reject verdict

**File:** `feature-requests/FR-024-codebase-quality-audit/mockk-investigation.md`

### BR-7: CLAUDE.md Vibe Coding Warning

**Current state:** CLAUDE.md has 16 Critical Engineering Constraints and 5 Testing Conventions. Neither section references the vibe coding anti-pattern documented in PM-008 and PM-010.

**Fix:** Add constraint #17 to the Critical Engineering Constraints section:

> **Never implement features in a single unstructured session ("vibe coding")** — PM-008 and PM-010 document how single-session implementation without the feature-request workflow produces green builds with broken features: silent data loss, missing transaction boundaries, untested error paths, and constraint violations that unit tests cannot catch. Always use the 6-phase feature-request workflow (`.claude/skills/feature-request-v2/`). If time constraints prevent full workflow execution, explicitly document which phases were skipped and what risks that introduces.

**File:** `CLAUDE.md` (project root)

## Architecture Decisions

### Decision 1: Conservative ArchUnit Rule 4 with allowlist

**Choice:** Rule 4 flags ALL `@EventListener` methods handling shared events, with a hardcoded allowlist for known-acceptable cases.

**Why:** A permissive rule (e.g., only flagging events whose name contains "Vendor" or "Order") would miss future violations. The conservative approach catches all cross-module listeners and forces explicit acknowledgment of any exception.

**Trade-off:** `PricingEngine.onPricingSignal()` will be added to the allowlist. If the team decides PricingEngine should also use `@TransactionalEventListener`, that can be done in a follow-up FR. The ArchUnit rule itself will be correct and future-proof.

### Decision 2: Throw exceptions for blank credentials instead of returning empty results

**Choice:** Carrier adapters (`UpsRateAdapter`, `FedExRateAdapter`, `UspsRateAdapter`) and `StripeProcessingFeeProvider` throw `ProviderUnavailableException` when credentials are blank, rather than returning a zero-money result.

**Why:** Returning `Money.ZERO` for a carrier rate would silently produce a cost envelope with zero shipping cost, which would pass the stress test incorrectly. Throwing `ProviderUnavailableException` triggers the existing error handling path (circuit breaker, retry, then failure). This matches the behavior that would occur if the credentials were present but the API was down.

**Exception:** `AmazonCreatorsApiAdapter.fetch()` already returns `emptyList()` for blank credentials (correctly per constraint #13, since `DemandSignalProvider.fetch()` returns `List<RawCandidate>`). No change needed there.

### Decision 3: Post-persist verification reads within same REQUIRES_NEW transaction

**Choice:** The verification read in `PricingInitializer` occurs within the same `REQUIRES_NEW` transaction that performed the write.

**Why:** Reading after `pricingEngine.setInitialPrice()` but before the `REQUIRES_NEW` transaction commits will see the unflushed entity in the persistence context. If the entity is there, the write was accepted by Hibernate. If the transaction later fails to commit (constraint violation), the outer exception handling will catch it. This gives us the best signal available within the transaction boundary.

### Decision 4: VendorSlaBreachRefunder unit tests remain unit tests

**Choice:** The existing 6 unit tests for `VendorSlaBreachRefunder` stay as Mockito unit tests. They do not need conversion to integration tests.

**Why:** The annotation change (`@EventListener` -> `@TransactionalEventListener`) does not affect the business logic tested by these unit tests. The unit tests call `refunder.onVendorSlaBreached(event)` directly — they do not go through Spring's event mechanism. The annotation correctness is verified by ArchUnit Rule 1 (AFTER_COMMIT must pair with REQUIRES_NEW) and Rule 4 (no plain @EventListener for cross-module events). Integration test coverage for the event chain (vendor publishes -> fulfillment handles) already exists in `VendorBreachIntegrationTest`.

## Layer-by-Layer Implementation

Since this is an audit organized by concern rather than traditional handler/domain/proxy layers, tasks are grouped by BR.

### Group 1: BR-1 — VendorSlaBreachRefunder Transaction Fix

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/VendorSlaBreachRefunder.kt`

Changes:
1. Replace `import org.springframework.context.event.EventListener` with `import org.springframework.transaction.event.TransactionalEventListener` and `import org.springframework.transaction.event.TransactionPhase`
2. Replace `import org.springframework.transaction.annotation.Transactional` with `import org.springframework.transaction.annotation.Transactional` and `import org.springframework.transaction.annotation.Propagation`
3. Replace `@EventListener` (line 21) with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
4. Replace `@Transactional` (line 22) with `@Transactional(propagation = Propagation.REQUIRES_NEW)`

**Test file:** `modules/fulfillment/src/test/kotlin/com/autoshipper/fulfillment/domain/service/VendorSlaBreachRefunderTest.kt`

No changes needed. The tests call `refunder.onVendorSlaBreached(event)` directly, bypassing Spring's event infrastructure. The annotation change does not affect the method's behavior when called directly. All 6 existing tests pass unchanged.

### Group 2: BR-2 — @Value Empty Defaults

#### 2a. ExternalApiConfig

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt`

Changes to `@Value` annotations:
- `@Value("\${ups.api.base-url}")` -> `@Value("\${ups.api.base-url:}")`
- `@Value("\${fedex.api.base-url}")` -> `@Value("\${fedex.api.base-url:}")`
- `@Value("\${usps.api.base-url}")` -> `@Value("\${usps.api.base-url:}")`
- `@Value("\${stripe.api.base-url}")` -> `@Value("\${stripe.api.base-url:}")`
- `@Value("\${shopify.api.base-url}")` -> `@Value("\${shopify.api.base-url:}")`

Guard: Each `@Bean` method checks `if (baseUrl.isBlank())` and, if so, returns a `RestClient` with a placeholder base URL (e.g., `http://unconfigured`) and logs a warning. The downstream adapter's own blank-credential guard will prevent any actual API calls.

#### 2b. UpsRateAdapter

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UpsRateAdapter.kt`

Changes:
- `@Value("\${ups.api.client-id}")` -> `@Value("\${ups.api.client-id:}")`
- `@Value("\${ups.api.client-secret}")` -> `@Value("\${ups.api.client-secret:}")`
- Add guard at top of `getRate()`:
  ```kotlin
  if (clientId.isBlank() || clientSecret.isBlank()) {
      logger.warn("UPS API credentials are blank — cannot fetch rates")
      throw ProviderUnavailableException("UPS", IllegalStateException("UPS API credentials not configured"))
  }
  ```
- Add `private val logger = LoggerFactory.getLogger(UpsRateAdapter::class.java)` and required import

#### 2c. FedExRateAdapter

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapter.kt`

Changes:
- `@Value("\${fedex.api.client-id}")` -> `@Value("\${fedex.api.client-id:}")`
- `@Value("\${fedex.api.client-secret}")` -> `@Value("\${fedex.api.client-secret:}")`
- Add guard at top of `getRate()`:
  ```kotlin
  if (clientId.isBlank() || clientSecret.isBlank()) {
      logger.warn("FedEx API credentials are blank — cannot fetch rates")
      throw ProviderUnavailableException("FedEx", IllegalStateException("FedEx API credentials not configured"))
  }
  ```
- Add `private val logger = LoggerFactory.getLogger(FedExRateAdapter::class.java)` and required import

#### 2d. UspsRateAdapter

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UspsRateAdapter.kt`

Changes:
- `@Value("\${usps.api.oauth-token}")` -> `@Value("\${usps.api.oauth-token:}")`
- Add guard at top of `getRate()`:
  ```kotlin
  if (oauthToken.isBlank()) {
      logger.warn("USPS API token is blank — cannot fetch rates")
      throw ProviderUnavailableException("USPS", IllegalStateException("USPS API token not configured"))
  }
  ```
- Add `private val logger = LoggerFactory.getLogger(UspsRateAdapter::class.java)` and required import

#### 2e. StripeProcessingFeeProvider

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StripeProcessingFeeProvider.kt`

Changes:
- `@Value("\${stripe.api.secret-key}")` -> `@Value("\${stripe.api.secret-key:}")`
- Add guard at top of `getFee()`:
  ```kotlin
  if (secretKey.isBlank()) {
      logger.warn("Stripe API secret key is blank — cannot fetch processing fee")
      throw ProviderUnavailableException("Stripe", IllegalStateException("Stripe API secret key not configured"))
  }
  ```
- Add `private val logger = LoggerFactory.getLogger(StripeProcessingFeeProvider::class.java)` and required import

#### 2f. StripeRefundAdapter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/payment/StripeRefundAdapter.kt`

Changes:
- `@Value("\${stripe.api.secret-key}")` -> `@Value("\${stripe.api.secret-key:}")`
- Add guard at top of `refund()`:
  ```kotlin
  if (secretKey.isBlank()) {
      logger.warn("Stripe API secret key is blank — cannot process refund")
      throw IllegalStateException("Stripe API secret key not configured")
  }
  ```
- Add `private val logger = LoggerFactory.getLogger(StripeRefundAdapter::class.java)` and required import

**Note:** `StripeRefundAdapter` constructs its own `RestClient` in the initializer block using `$secretKey`. When `secretKey` is blank, the `RestClient` will have `Authorization: Bearer ` (empty token). The guard in `refund()` catches this before any API call is made.

### Group 3: BR-3 — URL-Encode OAuth Token Bodies

#### 3a. FedExRateAdapter

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapter.kt`

Change in `fetchBearerToken()` (line 53):
```kotlin
// Before:
.body("grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret")

// After:
.body("grant_type=client_credentials" +
    "&client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
    "&client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}")
```

Add imports: `import java.net.URLEncoder` and `import java.nio.charset.StandardCharsets`

#### 3b. AmazonCreatorsApiAdapter

**File:** `modules/portfolio/src/main/kotlin/com/autoshipper/portfolio/proxy/AmazonCreatorsApiAdapter.kt`

Change in `getAccessToken()` (line 107):
```kotlin
// Before:
.body("grant_type=client_credentials&client_id=$credentialId&client_secret=$credentialSecret")

// After:
.body("grant_type=client_credentials" +
    "&client_id=${URLEncoder.encode(credentialId, StandardCharsets.UTF_8)}" +
    "&client_secret=${URLEncoder.encode(credentialSecret, StandardCharsets.UTF_8)}")
```

Add imports: `import java.net.URLEncoder` and `import java.nio.charset.StandardCharsets`

### Group 4: BR-4 — ArchUnit Rule 4

**File:** `modules/app/src/test/kotlin/com/autoshipper/ArchitectureTest.kt`

Add new test method and custom condition:

```kotlin
/**
 * Rule 4: @EventListener methods must not handle events from com.autoshipper.shared.events.
 *
 * Cross-module event listeners must use @TransactionalEventListener(AFTER_COMMIT) +
 * @Transactional(REQUIRES_NEW) per CLAUDE.md constraint #6. Plain @EventListener is only
 * acceptable for same-module listeners that should rollback with the publisher. Since all
 * domain events live in the shared module, any @EventListener handling a shared event type
 * is a cross-module listener that requires the double-annotation pattern.
 *
 * Known allowlist: PricingEngine.onPricingSignal — tracked for future migration.
 *
 * Violations found by: PM-001, PM-005, PM-006 (VendorSlaBreachRefunder was the last instance)
 */
@Test
fun `EventListener methods must not handle shared domain events`() {
    // ... rule implementation
}
```

Custom condition: `notHandleSharedEventsWithEventListener(allowlist: Set<String>)`
- Scans all methods annotated with `@EventListener`
- For each, inspects the method's parameter types
- If any parameter type's package starts with `com.autoshipper.shared.events`, it's a violation
- Unless the method's full name is in the allowlist

Allowlist: `setOf("com.autoshipper.pricing.domain.service.PricingEngine.onPricingSignal(com.autoshipper.shared.events.PricingSignal)")`

### Group 5: BR-5 — PricingInitializer Post-Persist Log

**File:** `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt`

After the `pricingEngine.setInitialPrice(...)` call (line 67), add:
```kotlin
val persisted = skuPriceRepository.findBySkuId(skuId.value)
if (persisted != null) {
    log.info(
        "Post-persist verification: SkuPriceEntity persisted for SKU {} — price={} {}, margin={}%",
        skuId, persisted.currentPriceAmount, persisted.currency, persisted.currentMarginPercent
    )
} else {
    log.error(
        "Post-persist verification FAILED: SkuPriceEntity NOT found for SKU {} after setInitialPrice()",
        skuId
    )
}
```

Remove or adjust the existing log on line 68 to avoid duplication. The existing log becomes redundant since the post-persist verification log includes all the same information.

### Group 6: BR-6 — mockk Investigation Document

**File:** `feature-requests/FR-024-codebase-quality-audit/mockk-investigation.md`

Write a structured investigation document covering:
1. Problem statement (Mockito + Kotlin value class limitation)
2. Reproduction scenario with `SkuId`
3. mockk's handling of value classes
4. Migration effort estimate
5. Recommendation

### Group 7: BR-7 — CLAUDE.md Vibe Coding Warning

**File:** `CLAUDE.md`

Add constraint #17 after the existing constraint #16 in the "Critical Engineering Constraints" section.

## Task Breakdown

### BR-1: VendorSlaBreachRefunder Transaction Fix
- [x] Update `VendorSlaBreachRefunder.kt`: replace `@EventListener` with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
- [x] Update `VendorSlaBreachRefunder.kt`: replace `@Transactional` with `@Transactional(propagation = Propagation.REQUIRES_NEW)`
- [x] Update imports in `VendorSlaBreachRefunder.kt`: add `TransactionalEventListener`, `TransactionPhase`, `Propagation`; remove `EventListener`
- [x] Verify all 6 existing `VendorSlaBreachRefunderTest` tests pass unchanged

### BR-2: @Value Empty Defaults
- [x] Update `ExternalApiConfig.kt`: add `:}` to all 5 `@Value` annotations
- [x] Update `ExternalApiConfig.kt`: add blank-URL guards with warning log to each `@Bean` method
- [x] Update `UpsRateAdapter.kt`: add `:}` to 2 `@Value` annotations
- [x] Update `UpsRateAdapter.kt`: add blank-credential guard with warning log at top of `getRate()`
- [x] Update `FedExRateAdapter.kt`: add `:}` to 2 `@Value` annotations
- [x] Update `FedExRateAdapter.kt`: add blank-credential guard with warning log at top of `getRate()`
- [x] Update `UspsRateAdapter.kt`: add `:}` to 1 `@Value` annotation
- [x] Update `UspsRateAdapter.kt`: add blank-token guard with warning log at top of `getRate()`
- [x] Update `StripeProcessingFeeProvider.kt`: add `:}` to 1 `@Value` annotation
- [x] Update `StripeProcessingFeeProvider.kt`: add blank-key guard with warning log at top of `getFee()`
- [x] Update `StripeRefundAdapter.kt`: add `:}` to 1 `@Value` annotation
- [x] Update `StripeRefundAdapter.kt`: add blank-key guard with warning log at top of `refund()`

### BR-3: URL-Encode OAuth Token Bodies
- [x] Update `FedExRateAdapter.fetchBearerToken()`: URL-encode `clientId` and `clientSecret` in form body
- [x] Add `URLEncoder` and `StandardCharsets` imports to `FedExRateAdapter.kt`
- [x] Update `AmazonCreatorsApiAdapter.getAccessToken()`: URL-encode `credentialId` and `credentialSecret` in form body
- [x] Add `URLEncoder` and `StandardCharsets` imports to `AmazonCreatorsApiAdapter.kt`

### BR-4: ArchUnit Rule 4
- [x] Add `notHandleSharedEventsWithEventListener()` custom condition to `ArchitectureTest.kt`
- [x] Add Rule 4 test method with allowlist for `PricingEngine.onPricingSignal`
- [x] Add `import org.springframework.context.event.EventListener` to ArchitectureTest imports
- [x] Verify Rule 4 passes (VendorSlaBreachRefunder fixed by BR-1, PricingEngine in allowlist)

### BR-5: PricingInitializer Post-Persist Log
- [x] Add post-persist verification read and INFO log after `setInitialPrice()` call in `PricingInitializer.kt`
- [x] Add ERROR-level log for verification failure case
- [x] Remove or consolidate redundant log on line 68
- [x] Verify `PricingInitializerIntegrationTest` passes (both tests)

### BR-6: mockk Investigation
- [x] Research Mockito limitation with Kotlin inline/value classes
- [x] Research mockk's value class support
- [x] Estimate migration effort (268 imports, 52 files)
- [x] Write `mockk-investigation.md` in feature request directory with recommendation

### BR-7: CLAUDE.md Vibe Coding Warning
- [x] Add constraint #17 to Critical Engineering Constraints section of `CLAUDE.md`
- [x] Reference PM-008 and PM-010 in the constraint text

### Final Verification
- [x] Run `./gradlew build` — zero test failures
- [x] Verify ArchUnit Rule 4 passes with VendorSlaBreachRefunder fixed and PricingEngine allowlisted
- [x] Verify all 6 VendorSlaBreachRefunderTest tests pass
- [x] Verify both PricingInitializerIntegrationTest tests pass

## Testing Strategy

### Tests Updated

| Test | Change | Reason |
|---|---|---|
| `VendorSlaBreachRefunderTest` (6 tests) | **None** | Tests call the method directly; annotations don't affect direct invocation |
| `PricingInitializerIntegrationTest` (2 tests) | **None** | The post-persist log addition doesn't change behavior; existing assertions remain valid |

### New Tests

| Test | Module | What It Verifies |
|---|---|---|
| ArchUnit Rule 4 in `ArchitectureTest` | app | `@EventListener` methods do not handle `com.autoshipper.shared.events.*` types (with allowlist) |

### Tests NOT Needed

- **Adapter @Value defaults:** These are `@Profile("!local")` beans that don't load in the test profile. The guards are defensive code that only activates under misconfiguration. Testing would require WireMock integration tests for each carrier, which is out of scope (the adapters have no existing tests).
- **URL-encoding:** The URL-encoding change is a one-line string transformation. Testing it would require either a real OAuth endpoint or a WireMock stub, both out of scope. Correctness is verified by code review.
- **Post-persist log:** The log addition is observability-only. The existing `PricingInitializerIntegrationTest` already verifies the persist works correctly.

### Regression Protection

- ArchUnit Rule 1 (existing) verifies that `VendorSlaBreachRefunder`'s new `@TransactionalEventListener(AFTER_COMMIT)` is paired with `@Transactional(REQUIRES_NEW)`.
- ArchUnit Rule 4 (new) verifies no plain `@EventListener` handles shared events (prevents regression to the old pattern).
- All existing 6 `VendorSlaBreachRefunderTest` tests continue to pass unchanged.
- Both existing `PricingInitializerIntegrationTest` tests continue to pass unchanged.
- Full `./gradlew build` must be green.

## Rollout Plan

### Deployment

This audit is a zero-risk deployment since it fixes existing bugs and adds defensive guards:

1. **Pre-merge:** Run `./gradlew build` to verify zero test failures.
2. **Merge order:** All changes can be merged in a single PR since they are independent and non-behavioral. However, commits should be organized per-BR for reviewability (per NFR-3):
   - Commit 1: BR-1 (VendorSlaBreachRefunder annotation fix)
   - Commit 2: BR-2 (all @Value empty defaults + guards)
   - Commit 3: BR-3 (URL-encoding fixes)
   - Commit 4: BR-4 (ArchUnit Rule 4)
   - Commit 5: BR-5 (PricingInitializer post-persist log)
   - Commit 6: BR-6 (mockk investigation document)
   - Commit 7: BR-7 (CLAUDE.md update)
3. **Post-merge:** No database migrations. No new dependencies. No configuration changes.

### Rollback

Each commit is independently revertable. If a specific change causes issues:
- BR-1 revert: Restores `@EventListener` + `@Transactional` (re-introduces the bug but is safe in terms of test pass/fail)
- BR-2 revert: Removes empty defaults (safe unless running in a profile that lacks the properties)
- BR-3 revert: Removes URL encoding (re-introduces the vulnerability but doesn't change behavior for normal credentials)
- BR-4 revert: Removes ArchUnit Rule 4 (no production impact)
- BR-5 revert: Removes verification log (no production impact)
- BR-6/BR-7 revert: Documentation-only

### Monitoring

After deployment, verify:
- Application starts successfully in all profiles (local, test, production)
- `VendorSlaBreachRefunder` log messages appear with correct "Vendor SLA breached" pattern when SLA breaches occur
- `PricingInitializer` now shows "Post-persist verification: SkuPriceEntity persisted" in logs when SKUs transition to LISTED
- No `ProviderUnavailableException` errors from blank-credential guards in production (credentials should be configured)
