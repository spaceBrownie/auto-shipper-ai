# Post-Mortem: ShopifyPriceSyncAdapter Startup Failure Under `local` Profile

**Date:** 2026-03-04
**Severity:** P1 (application fails to start)
**Duration:** Development-time only; caught before deployment
**Feature Context:** FR-015 (validate-state-machine)

---

## Summary

The application crashed on startup when running with `SPRING_PROFILES_ACTIVE=local`. The root cause was an incomplete profile-gating change: `ExternalApiConfig` (which creates all external `RestClient` beans) was annotated with `@Profile("!local")`, but `ShopifyPriceSyncAdapter` in the `:pricing` module still had a hard dependency on the `shopifyRestClient` bean via `@Qualifier`. Since that bean no longer existed under the `local` profile, Spring's dependency injection failed and the application context could not start.

---

## Timeline

1. **Fix 1 of FR-015** added `@Profile("!local")` to `ExternalApiConfig` in `:catalog` so that external API `RestClient` beans (UPS, FedEx, USPS, Stripe, Shopify) would not be created in local development. Stub providers were added for carrier rates, processing fees, and platform fees in the `:catalog` module.
2. The `:pricing` module's `ShopifyPriceSyncAdapter` was **not** updated in that same change. It continued to inject `@Qualifier("shopifyRestClient") RestClient` directly.
3. On startup with the `local` profile, Spring attempted to wire `ShopifyPriceSyncAdapter`, could not find a `RestClient` bean named `shopifyRestClient`, and threw a fatal `UnsatisfiedDependencyException`.
4. Because `PricingDecisionListener` depends on `ShopifyPriceSyncAdapter`, the failure cascaded — the entire pricing event-handling subsystem failed to initialize.

---

## Root Cause

**Cross-module dependency on a profile-gated bean without a corresponding stub.**

`ExternalApiConfig` lives in `:catalog` and produces five `RestClient` beans. When it was excluded from the `local` profile, all five beans disappeared. The `:catalog` module's own consumers were handled (stub carrier rate providers, stub processing fee provider, stub platform fee provider were all created). However, `ShopifyPriceSyncAdapter` in the `:pricing` module was a cross-module consumer of the `shopifyRestClient` bean that was missed during the initial change.

The error message from Spring:

```
Parameter 0 of constructor in
com.autoshipper.pricing.proxy.ShopifyPriceSyncAdapter required a bean
of type 'org.springframework.web.client.RestClient' that could not be found.
```

### Contributing Factors

- **No compile-time safety net for Spring bean wiring.** The `@Qualifier("shopifyRestClient")` dependency is resolved at runtime, so the mismatch was invisible until the application actually started.
- **Cross-module bean dependency.** The `:pricing` module depended on a bean defined in `:catalog`'s configuration. This coupling made it easy to overlook when modifying `:catalog`.
- **Incomplete audit of bean consumers.** When `@Profile("!local")` was added to `ExternalApiConfig`, only `:catalog`-internal consumers were addressed. A search for all usages of the five `RestClient` beans across all modules would have caught this.

---

## Resolution

Applied the same interface-extraction pattern already used for carrier rates, processing fees, and platform fees in `:catalog`:

1. **Extracted `PriceSyncAdapter` interface** (`/modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/PriceSyncAdapter.kt`):
   ```kotlin
   interface PriceSyncAdapter {
       fun syncPrice(skuId: SkuId, newPrice: Money)
   }
   ```

2. **Profile-gated the real implementation** (`ShopifyPriceSyncAdapter.kt`):
   ```kotlin
   @Component
   @Profile("!local")
   class ShopifyPriceSyncAdapter(
       @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient
   ) : PriceSyncAdapter { ... }
   ```

3. **Created a stub for local development** (`StubPriceSyncAdapter.kt`):
   ```kotlin
   @Component
   @Profile("local")
   class StubPriceSyncAdapter : PriceSyncAdapter {
       override fun syncPrice(skuId: SkuId, newPrice: Money) {
           log.info("[STUB] Would sync price for SKU {} to Shopify: {}", skuId, newPrice)
       }
   }
   ```

4. **Updated `PricingDecisionListener`** to depend on the `PriceSyncAdapter` interface instead of the concrete `ShopifyPriceSyncAdapter` class.

### Files Changed

| File | Change |
|---|---|
| `modules/pricing/src/main/kotlin/.../proxy/PriceSyncAdapter.kt` | New interface |
| `modules/pricing/src/main/kotlin/.../proxy/ShopifyPriceSyncAdapter.kt` | Implements interface, added `@Profile("!local")` |
| `modules/pricing/src/main/kotlin/.../proxy/StubPriceSyncAdapter.kt` | New stub for `local` profile |
| `modules/pricing/src/main/kotlin/.../domain/service/PricingDecisionListener.kt` | Depends on `PriceSyncAdapter` interface |

---

## Lessons Learned

### What went well
- The failure was caught immediately during local development, not in a deployed environment.
- The fix followed an established pattern (interface + profile-gated stub) already proven in the `:catalog` module.
- The stub approach preserves the ability to run and test the full application locally without external API credentials.

### What went wrong
- When profile-gating `ExternalApiConfig`, the impact analysis only covered the `:catalog` module. The cross-module consumer in `:pricing` was missed.
- There was no integration test or startup smoke test that exercised the `local` profile to catch missing beans automatically.

---

## Action Items

| # | Action | Priority |
|---|---|---|
| 1 | **Add a `local`-profile startup smoke test** that boots the full application context with `SPRING_PROFILES_ACTIVE=local` and verifies the context loads successfully. This catches missing bean definitions before they reach manual testing. | High |
| 2 | **Audit all remaining cross-module bean dependencies.** Any component in `:pricing`, `:vendor`, `:fulfillment`, or other modules that injects a `RestClient` or other bean from `ExternalApiConfig` must have a corresponding stub under the `local` profile. | High |
| 3 | **Establish a convention: external adapter interfaces live in the consuming module, not as concrete classes injecting cross-module beans.** Every external integration point should follow the pattern: interface in the domain/proxy layer, real implementation profile-gated with `@Profile("!local")`, stub with `@Profile("local")`. | Medium |
| 4 | **Consider moving `ExternalApiConfig` to `:shared` or splitting it per module** so each module owns its own `RestClient` bean definitions. This eliminates the cross-module bean coupling entirely. | Medium |
