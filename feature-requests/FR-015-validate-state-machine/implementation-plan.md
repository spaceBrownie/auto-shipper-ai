# FR-015: Validate State Machine — Implementation Plan

## Technical Design

This feature request addresses three gaps discovered during endpoint testing of the SKU lifecycle state machine. The fixes are independent of each other but collectively complete the local development and API experience.

### Fix 1: Local Dev Stub Providers

**Goal:** Enable `POST /api/skus/{id}/verify-costs` to work locally without external API keys.

**Architecture:**

1. **Extract interfaces** for Stripe and Shopify providers. `CostGateService` currently depends on the concrete classes `StripeProcessingFeeProvider` and `ShopifyPlatformFeeProvider`. We introduce `ProcessingFeeProvider` and `PlatformFeeProvider` interfaces that match the existing method signatures, then update `CostGateService` to depend on these interfaces.

2. **Profile-gate live providers.** All five external provider components (`FedExRateAdapter`, `UpsRateAdapter`, `UspsRateAdapter`, `StripeProcessingFeeProvider`, `ShopifyPlatformFeeProvider`) and the `ExternalApiConfig` that creates their `RestClient` beans get `@Profile("!local")` so they do not load when `spring.profiles.active=local`.

3. **Create stub providers** under `@Profile("local")`. A `StubCarrierRateConfiguration` `@Configuration` class registers three `CarrierRateProvider` beans (FedEx, UPS, USPS) with realistic hardcoded rates. Separate `StubProcessingFeeProvider` and `StubPlatformFeeProvider` components implement the new interfaces with deterministic formulas.

**Data flow (local profile):**

```
CostGateService
  ├── carrierRateProviders: List<CarrierRateProvider>  ← 3 stub beans
  ├── processingFeeProvider: ProcessingFeeProvider      ← StubProcessingFeeProvider
  └── platformFeeProvider: PlatformFeeProvider          ← StubPlatformFeeProvider
```

### Fix 2: Wire Initial Pricing on SKU Listing

**Goal:** When a SKU transitions to `LISTED`, automatically create its pricing record so `GET /api/skus/{id}/pricing` returns data.

**Architecture:** A new `PricingInitializer` component in the `:pricing` module listens for `SkuStateChanged` events. When `toState == "LISTED"`, it reads the most recent `StressTestResultEntity` and `CostEnvelopeEntity` for the SKU, computes margin, and calls `PricingEngine.setInitialPrice()`. This follows the existing cross-module read pattern already used by `PricingEngine` (which imports `CostEnvelopeRepository` from `:catalog`).

The listener uses `@TransactionalEventListener(phase = AFTER_COMMIT)` to ensure the state transition transaction has committed before reading stress test data. It is idempotent: if a `SkuPriceEntity` already exists for the SKU, it skips initialization.

**Data flow:**

```
SkuService.transition(skuId, LISTED)
  → publishes SkuStateChanged(toState = "LISTED")
    → PricingInitializer.onSkuListed()
      → reads StressTestResultEntity (estimatedPriceAmount, stressedTotalCostAmount)
      → reads CostEnvelopeEntity (for fullyBurdenedCost)
      → computes margin = (price - cost) / price * 100
      → calls PricingEngine.setInitialPrice(skuId, price, margin, fullyBurdenedCost)
```

### Fix 3: Global Exception Handler

**Goal:** Map domain exceptions to appropriate HTTP status codes with structured JSON error bodies.

**Architecture:** A single `@ControllerAdvice` class in the `:catalog` handler package handles three exception types:

| Exception | HTTP Status | Rationale |
|---|---|---|
| `InvalidSkuTransitionException` | 409 Conflict | Request conflicts with current resource state |
| `ProviderUnavailableException` | 502 Bad Gateway | Upstream dependency failure |
| `NoSuchElementException` | 404 Not Found | Requested resource does not exist |

Response body format:
```json
{
  "error": "INVALID_STATE_TRANSITION",
  "message": "Invalid SKU transition from IDEATION to LISTED"
}
```

Logging: 4xx responses at WARN level, 502 at ERROR level.

---

## Architecture Decisions

### AD-1: Interface Extraction for Stripe and Shopify

**Decision:** Extract `ProcessingFeeProvider` and `PlatformFeeProvider` interfaces.

**Why not have stubs extend concrete classes?** The concrete classes (`StripeProcessingFeeProvider`, `ShopifyPlatformFeeProvider`) inject `RestClient` beans and `@Value` properties in their constructors. Extending them would require stubs to satisfy those constructor parameters, which defeats the purpose of avoiding external dependencies locally. Interface extraction is cleaner, enables constructor-injection test doubles without Mockito, and aligns with the dependency inversion principle.

**Why not also create an interface for carriers?** `CarrierRateProvider` is already an interface. The three carrier adapters implement it. No extraction needed.

### AD-2: `@Profile("!local")` Approach

**Decision:** Use Spring `@Profile("!local")` on live providers and `@Profile("local")` on stubs.

**Alternatives considered:**
- `@ConditionalOnProperty`: More granular but requires per-provider config properties. Overkill for this use case.
- `@Primary` on stubs: Risky — both live and stub beans would load, only injection priority changes. Could cause startup failures if live beans fail to initialize.

`@Profile` is the standard Spring mechanism for environment-specific bean selection and keeps it simple.

### AD-3: `@TransactionalEventListener` for Pricing Initialization

**Decision:** Use `@TransactionalEventListener(phase = AFTER_COMMIT)` rather than `@EventListener`.

**Rationale:** The `SkuStateChanged` event is published inside the `SkuService.transition()` transaction. The pricing initializer needs to read the stress test result and cost envelope data that was persisted in a prior transaction. Using `AFTER_COMMIT` ensures the transition has completed and the data is visible. This also matches the pattern already used by `PricingDecisionListener`.

### AD-4: `@ControllerAdvice` in `:catalog` Handler Package

**Decision:** Place the exception handler in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/GlobalExceptionHandler.kt`.

**Rationale:** All three exception types (`InvalidSkuTransitionException`, `ProviderUnavailableException`, `StressTestFailedException`) are defined in the `:catalog` module. The controllers are also in `:catalog`. When future modules (vendor, fulfillment, etc.) add their own controllers, they can either reuse this handler (if the app module scans it) or define module-specific handlers. For now, a single handler covers all active controllers.

---

## Layer-by-Layer Implementation

### Proxy Layer (Fix 1)

#### 1. Create `ProcessingFeeProvider` Interface

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/ProcessingFeeProvider.kt`

```kotlin
package com.autoshipper.catalog.proxy.payment

import com.autoshipper.shared.money.Money

interface ProcessingFeeProvider {
    fun getFee(estimatedOrderValue: Money): Money
}
```

#### 2. Create `PlatformFeeProvider` Interface

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformFeeProvider.kt`

```kotlin
package com.autoshipper.catalog.proxy.platform

import com.autoshipper.shared.money.Money

interface PlatformFeeProvider {
    fun getFee(): Money
}
```

#### 3. Update `StripeProcessingFeeProvider`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StripeProcessingFeeProvider.kt`

Changes:
- Add `@Profile("!local")` annotation
- Implement `ProcessingFeeProvider` interface
- Existing `fun getFee(estimatedOrderValue: Money): Money` signature already matches the interface; add `override` keyword

#### 4. Update `ShopifyPlatformFeeProvider`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyPlatformFeeProvider.kt`

Changes:
- Add `@Profile("!local")` annotation
- Implement `PlatformFeeProvider` interface
- Existing `fun getFee(): Money` signature already matches; add `override` keyword

#### 5. Profile-Gate Carrier Adapters

**Files:**
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapter.kt` — add `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UpsRateAdapter.kt` — add `@Profile("!local")`
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UspsRateAdapter.kt` — add `@Profile("!local")`

#### 6. Create `StubCarrierRateConfiguration`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/StubCarrierRateConfiguration.kt`

```kotlin
package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.math.BigDecimal

@Configuration
@Profile("local")
class StubCarrierRateConfiguration {

    @Bean
    fun stubFedExRateProvider(): CarrierRateProvider = StubCarrierRateProvider("FedEx", BigDecimal("7.99"))

    @Bean
    fun stubUpsRateProvider(): CarrierRateProvider = StubCarrierRateProvider("UPS", BigDecimal("8.49"))

    @Bean
    fun stubUspsRateProvider(): CarrierRateProvider = StubCarrierRateProvider("USPS", BigDecimal("5.99"))
}

class StubCarrierRateProvider(
    override val carrierName: String,
    private val fixedRate: BigDecimal
) : CarrierRateProvider {
    override fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money =
        Money.of(fixedRate, Currency.USD)
}
```

#### 7. Create `StubProcessingFeeProvider`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StubProcessingFeeProvider.kt`

```kotlin
package com.autoshipper.catalog.proxy.payment

import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
@Profile("local")
class StubProcessingFeeProvider : ProcessingFeeProvider {
    companion object {
        private val STRIPE_PERCENTAGE_RATE = BigDecimal("0.029")
        private val STRIPE_FIXED_FEE = BigDecimal("0.30")
    }

    override fun getFee(estimatedOrderValue: Money): Money {
        val percentageFee = estimatedOrderValue.normalizedAmount
            .multiply(STRIPE_PERCENTAGE_RATE)
            .setScale(4, RoundingMode.HALF_UP)
        val totalFeeAmount = percentageFee
            .add(STRIPE_FIXED_FEE)
            .setScale(4, RoundingMode.HALF_UP)
        return Money.of(totalFeeAmount, estimatedOrderValue.currency)
    }
}
```

#### 8. Create `StubPlatformFeeProvider`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/StubPlatformFeeProvider.kt`

```kotlin
package com.autoshipper.catalog.proxy.platform

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@Profile("local")
class StubPlatformFeeProvider : PlatformFeeProvider {
    companion object {
        private val BASIC_PLAN_RATE = BigDecimal("0.020")
        private val DEFAULT_ORDER_VALUE = BigDecimal("100.00")
    }

    override fun getFee(): Money {
        val feeAmount = DEFAULT_ORDER_VALUE.multiply(BASIC_PLAN_RATE)
        return Money.of(feeAmount, Currency.USD)
    }
}
```

### Domain Layer (Fix 1 + Fix 2)

#### 9. Update `CostGateService` Constructor

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/CostGateService.kt`

Change constructor parameters:
- `private val stripeProcessingFeeProvider: StripeProcessingFeeProvider` → `private val processingFeeProvider: ProcessingFeeProvider`
- `private val shopifyPlatformFeeProvider: ShopifyPlatformFeeProvider` → `private val platformFeeProvider: PlatformFeeProvider`

Update imports:
- Remove `import com.autoshipper.catalog.proxy.payment.StripeProcessingFeeProvider`
- Remove `import com.autoshipper.catalog.proxy.platform.ShopifyPlatformFeeProvider`
- Add `import com.autoshipper.catalog.proxy.payment.ProcessingFeeProvider`
- Add `import com.autoshipper.catalog.proxy.platform.PlatformFeeProvider`

Update method body references:
- `stripeProcessingFeeProvider.getFee(estimatedOrderValue)` → `processingFeeProvider.getFee(estimatedOrderValue)`
- `shopifyPlatformFeeProvider.getFee()` → `platformFeeProvider.getFee()`

#### 10. Create `PricingInitializer`

**File:** `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt`

```kotlin
package com.autoshipper.pricing.domain.service

import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.shared.events.SkuStateChanged
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class PricingInitializer(
    private val pricingEngine: PricingEngine,
    private val skuPriceRepository: SkuPriceRepository,
    private val stressTestResultRepository: StressTestResultRepository,
    private val costEnvelopeRepository: CostEnvelopeRepository
) {
    private val log = LoggerFactory.getLogger(PricingInitializer::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onSkuStateChanged(event: SkuStateChanged) {
        if (event.toState != "LISTED") return

        val skuId = event.skuId

        // Idempotency guard: skip if price already exists
        if (skuPriceRepository.findBySkuId(skuId.value) != null) {
            log.info("Price record already exists for SKU {}; skipping initialization", skuId)
            return
        }

        val stressResult = stressTestResultRepository.findBySkuId(skuId.value)
            .maxByOrNull { it.testedAt }
        if (stressResult == null) {
            log.warn("No stress test result for SKU {}; cannot initialize pricing", skuId)
            return
        }

        val envelopeEntity = costEnvelopeRepository.findBySkuId(skuId.value)
        if (envelopeEntity == null) {
            log.warn("No cost envelope for SKU {}; cannot initialize pricing", skuId)
            return
        }

        val currency = Currency.valueOf(stressResult.currency)
        val price = Money.of(stressResult.estimatedPriceAmount, currency)
        val fullyBurdenedCost = Money.of(stressResult.stressedTotalCostAmount, currency)

        // margin = (price - cost) / price * 100
        val margin = if (price.normalizedAmount > BigDecimal.ZERO) {
            price.normalizedAmount.subtract(fullyBurdenedCost.normalizedAmount)
                .divide(price.normalizedAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        } else {
            BigDecimal.ZERO
        }

        pricingEngine.setInitialPrice(skuId, price, Percentage.of(margin), fullyBurdenedCost)
        log.info("Initialized pricing for SKU {}: price={}, margin={}%, cost={}", skuId, price, margin, fullyBurdenedCost)
    }
}
```

### Handler Layer (Fix 3)

#### 11. Create `GlobalExceptionHandler`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/GlobalExceptionHandler.kt`

```kotlin
package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.InvalidSkuTransitionException
import com.autoshipper.catalog.domain.ProviderUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(InvalidSkuTransitionException::class)
    fun handleInvalidTransition(ex: InvalidSkuTransitionException): ResponseEntity<Map<String, String?>> {
        log.warn("Invalid state transition: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "INVALID_STATE_TRANSITION", "message" to ex.message)
        )
    }

    @ExceptionHandler(ProviderUnavailableException::class)
    fun handleProviderUnavailable(ex: ProviderUnavailableException): ResponseEntity<Map<String, String?>> {
        log.error("External provider unavailable: {}", ex.message, ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            mapOf("error" to "PROVIDER_UNAVAILABLE", "message" to ex.message)
        )
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<Map<String, String?>> {
        log.warn("Resource not found: {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "NOT_FOUND", "message" to ex.message)
        )
    }
}
```

### Config Layer (Fix 1)

#### 12. Profile-Gate `ExternalApiConfig`

**File:** `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt`

Add `@Profile("!local")` to the class annotation:

```kotlin
@Configuration
@Profile("!local")
class ExternalApiConfig {
    // ... unchanged
}
```

---

## Task Breakdown

### Proxy Layer (Fix 1)

- [x] **Create `ProcessingFeeProvider` interface** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/ProcessingFeeProvider.kt` with `fun getFee(estimatedOrderValue: Money): Money`
- [x] **Create `PlatformFeeProvider` interface** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/PlatformFeeProvider.kt` with `fun getFee(): Money`
- [x] **Update `StripeProcessingFeeProvider`** — add `@Profile("!local")`, implement `ProcessingFeeProvider`, add `override` to `getFee()`
- [x] **Update `ShopifyPlatformFeeProvider`** — add `@Profile("!local")`, implement `PlatformFeeProvider`, add `override` to `getFee()`
- [x] **Add `@Profile("!local")` to `FedExRateAdapter`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/FedExRateAdapter.kt`
- [x] **Add `@Profile("!local")` to `UpsRateAdapter`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UpsRateAdapter.kt`
- [x] **Add `@Profile("!local")` to `UspsRateAdapter`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/UspsRateAdapter.kt`
- [x] **Create `StubCarrierRateConfiguration`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/carrier/StubCarrierRateConfiguration.kt` with `@Configuration @Profile("local")`, registers 3 `CarrierRateProvider` beans via `StubCarrierRateProvider` class (FedEx $7.99, UPS $8.49, USPS $5.99)
- [x] **Create `StubProcessingFeeProvider`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/payment/StubProcessingFeeProvider.kt` with `@Component @Profile("local")`, implements `ProcessingFeeProvider`, applies 2.9% + $0.30 formula
- [x] **Create `StubPlatformFeeProvider`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/StubPlatformFeeProvider.kt` with `@Component @Profile("local")`, implements `PlatformFeeProvider`, returns 2.0% of $100.00 default order value

### Domain Layer (Fix 1 + Fix 2)

- [x] **Update `CostGateService` constructor** — replace `StripeProcessingFeeProvider` with `ProcessingFeeProvider`, replace `ShopifyPlatformFeeProvider` with `PlatformFeeProvider`, update imports and field references (`stripeProcessingFeeProvider` to `processingFeeProvider`, `shopifyPlatformFeeProvider` to `platformFeeProvider`)
- [x] **Create `PricingInitializer`** — `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingInitializer.kt` with `@TransactionalEventListener(phase = AFTER_COMMIT)` for `SkuStateChanged`, reads `StressTestResultEntity` and `CostEnvelopeEntity`, computes margin, calls `PricingEngine.setInitialPrice()`, idempotent (skips if `SkuPriceEntity` already exists)

### Handler Layer (Fix 3)

- [x] **Create `GlobalExceptionHandler`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/GlobalExceptionHandler.kt` with `@ControllerAdvice`, maps `InvalidSkuTransitionException` to 409, `ProviderUnavailableException` to 502, `NoSuchElementException` to 404, structured JSON body `{"error": "...", "message": "..."}`

### Config Layer (Fix 1)

- [x] **Add `@Profile("!local")` to `ExternalApiConfig`** — `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt`, prevents RestClient beans from loading under `local` profile

### Verification

- [x] **Run `./gradlew build`** — confirm all existing tests pass with no regressions
- [x] **Manual smoke test with `local` profile** — start with `spring.profiles.active=local`, walk a SKU through the full lifecycle (create, transition to VALIDATION_PENDING, COST_GATING, verify-costs, stress-test, check pricing)
- [x] **Verify error mapping** — confirm invalid transition returns 409, non-existent SKU returns 404

---

## Testing Strategy

### Automated Tests (Existing)

Run `./gradlew build` after all changes. The existing test suite must pass without modification. Key test classes to monitor:
- `CostGateServiceTest` — may mock `StripeProcessingFeeProvider` and `ShopifyPlatformFeeProvider` by class; update mocks to use interface types
- `PricingEngineTest` — should be unaffected (no changes to `PricingEngine` API)
- `StressTestServiceTest` — should be unaffected

### Manual Integration Tests

1. **Local Profile Smoke Test:**
   - Start app with `SPRING_PROFILES_ACTIVE=local`
   - `POST /api/skus` — create a SKU
   - `POST /api/skus/{id}/state` with `{"state": "VALIDATION_PENDING"}` then `{"state": "COST_GATING"}`
   - `POST /api/skus/{id}/verify-costs` — must succeed with stub rates
   - `POST /api/skus/{id}/stress-test` with estimated price
   - `GET /api/skus/{id}/pricing` — must return 200 with pricing data

2. **Error Mapping Tests:**
   - `POST /api/skus/{id}/state` with `{"state": "LISTED"}` from IDEATION — expect 409
   - `GET /api/skus/{nonexistent-uuid}` — expect 404
   - (502 test requires simulating provider failure, which is covered by the existing `ProviderUnavailableException` handling path)

3. **Idempotency Test:**
   - Transition a listed SKU to PAUSED then back to LISTED
   - `GET /api/skus/{id}/pricing` — should still return the original pricing record (no duplicate)

---

## Rollout Plan

### Step 1: Implement Fix 1 (Proxy + Config Layer)

Create interfaces, update implementations, add profile annotations, create stubs, update `CostGateService`. Run `./gradlew build` to verify.

### Step 2: Implement Fix 2 (Domain Layer)

Create `PricingInitializer`. Run `./gradlew build` to verify.

### Step 3: Implement Fix 3 (Handler Layer)

Create `GlobalExceptionHandler`. Run `./gradlew build` to verify.

### Step 4: Full Verification

Run manual smoke test with `local` profile. Walk a SKU through the complete lifecycle. Verify pricing endpoint returns data for listed SKUs. Verify error responses return correct HTTP status codes.
