# PM-001: ShopifyPriceSyncAdapter Startup Crash Under Local Profile

**Date:** 2026-03-04
**Severity:** High
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

The application crashed on startup when running with `SPRING_PROFILES_ACTIVE=local` because `ShopifyPriceSyncAdapter` in the `:pricing` module injected a `shopifyRestClient` bean that no longer existed under the local profile. The bean was defined in `ExternalApiConfig`, which had just been annotated with `@Profile("!local")` as part of the FR-015 stub-provider work. The fix extracted a `PriceSyncAdapter` interface and provided a profile-switched stub implementation, following the same pattern already applied to carrier and payment adapters in the `:catalog` module.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-015 implementation began with three fixes: stub providers for external APIs, pricing initializer, and global exception handling |
| Fix 1 applied | `@Profile("!local")` added to `ExternalApiConfig` in `:catalog` module, disabling all external `RestClient` beans (UPS, FedEx, USPS, Stripe, Shopify) under the local profile. Stub carrier, payment, and platform providers created for local profile |
| Build + startup attempt | Application started with `SPRING_PROFILES_ACTIVE=local`. Spring context failed to initialize |
| Failure observed | `UnsatisfiedDependencyException`: "Parameter 0 of constructor in `com.autoshipper.pricing.proxy.ShopifyPriceSyncAdapter` required a bean of type `org.springframework.web.client.RestClient` that could not be found" |
| Root cause identified | `ShopifyPriceSyncAdapter` unconditionally required `@Qualifier("shopifyRestClient") RestClient`, but that bean was now excluded under local profile. The `:catalog` module adapters had been stubbed, but the `:pricing` module adapter was missed |
| Fix applied | Extracted `PriceSyncAdapter` interface, added `@Profile("!local")` to `ShopifyPriceSyncAdapter`, created `StubPriceSyncAdapter` with `@Profile("local")`, updated `PricingDecisionListener` to depend on the interface |
| Verified | Application started successfully under local profile |

## Symptom

When running the application with `SPRING_PROFILES_ACTIVE=local`, Spring Boot failed during context initialization with:

```
Parameter 0 of constructor in com.autoshipper.pricing.proxy.ShopifyPriceSyncAdapter
required a bean of type 'org.springframework.web.client.RestClient' that could not be found.
```

The application could not start at all. `PricingDecisionListener` depends on `ShopifyPriceSyncAdapter`, which depends on `shopifyRestClient`, creating a cascading dependency failure that prevented the entire Spring context from loading.

## Root Cause

The root cause was an **incomplete cross-module impact analysis** when applying profile gating to external API beans.

`ExternalApiConfig` in `modules/catalog/src/main/kotlin/com/autoshipper/catalog/config/ExternalApiConfig.kt` defines five `RestClient` beans: `upsRestClient`, `fedexRestClient`, `uspsRestClient`, `stripeRestClient`, and `shopifyRestClient`. When `@Profile("!local")` was added to this configuration class, all five beans became unavailable under the local profile.

The `:catalog` module's own consumers of these beans (carrier rate adapters, Stripe processing fee provider, Shopify platform fee provider) were simultaneously updated with stub replacements. However, the `shopifyRestClient` bean has a **cross-module consumer**: `ShopifyPriceSyncAdapter` in the `:pricing` module. This adapter was not profile-gated and had no stub alternative, so it continued to unconditionally demand the now-absent bean.

The deeper issue is that a configuration class in the `:catalog` module produces beans consumed by the `:pricing` module. This cross-module bean dependency was invisible during the change because:

1. There is no explicit dependency declaration between `:pricing` and the `ExternalApiConfig` class — the coupling is implicit through Spring's bean registry.
2. The adapters within `:catalog` were easy to find and update. The adapter in `:pricing` was in a different module and easy to miss.
3. No compile-time or static analysis tool flagged the broken dependency. It only surfaced at runtime during context initialization.

## Fix Applied

The fix followed the same interface-extraction pattern already used for carrier rate adapters and payment/platform fee providers in the `:catalog` module:

1. **Extracted an interface** to decouple the consumer from the implementation
2. **Profile-gated the real implementation** so it only loads when external APIs are available
3. **Created a stub** for local development that logs instead of making HTTP calls
4. **Updated the consumer** to depend on the interface, not the concrete class

### Files Changed

- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/PriceSyncAdapter.kt` — **New file.** Interface defining `syncPrice(skuId: SkuId, newPrice: Money)`. Decouples `PricingDecisionListener` from the Shopify-specific implementation.

- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/ShopifyPriceSyncAdapter.kt` — Added `@Profile("!local")` annotation and implemented `PriceSyncAdapter` interface. Only loaded when external APIs are available.

- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/proxy/StubPriceSyncAdapter.kt` — **New file.** `@Profile("local")` stub that logs price sync requests without making HTTP calls. Allows the pricing module to function in local development.

- `modules/pricing/src/main/kotlin/com/autoshipper/pricing/domain/service/PricingDecisionListener.kt` — Changed constructor parameter from `ShopifyPriceSyncAdapter` to `PriceSyncAdapter` (the interface). Spring now injects whichever profile-appropriate implementation is active.

## Impact

- **Blast radius:** Local development only. The `local` profile is not used in production, so deployed environments were unaffected.
- **User impact:** None. This was caught during development before any deployment.
- **Developer impact:** High within the session. The application was completely unable to start under the local profile, blocking all local development and testing until resolved.

## Lessons Learned

### What went well
- The error message from Spring was clear and immediately pointed to the missing bean and the consuming class
- The fix pattern was already established in the `:catalog` module (stub carrier/payment/platform adapters), so the solution was straightforward to apply
- The fix was small, isolated, and low-risk

### What could be improved
- When profile-gating a bean-producing configuration class, the impact analysis must extend beyond the module that owns the configuration. All consumers of those beans across the entire application need to be identified and updated
- The cross-module bean dependency (`:pricing` consuming a `RestClient` bean defined in `:catalog`) is an architectural smell. Bean definitions consumed across modules should ideally live in a shared configuration or in the consuming module itself
- There was no automated check (test or build-time validation) that would catch a missing bean under a specific profile before attempting to start the application

## Prevention

- [ ] Add a Spring Boot integration test that loads the application context under the `local` profile (`@SpringBootTest` with `@ActiveProfiles("local")`) — this would catch missing bean errors at test time rather than manual startup
- [ ] Add a similar integration test for the default (non-local) profile to verify all real adapters can be wired
- [ ] Consider relocating `ExternalApiConfig` (or at least the `shopifyRestClient` bean) to a shared configuration module or to the `:pricing` module itself, so that bean producers and consumers are co-located and profile changes are naturally scoped
- [ ] When adding `@Profile` annotations to configuration classes, use `grep` or IDE "find usages" across all modules for every bean name defined in the class, not just the current module
