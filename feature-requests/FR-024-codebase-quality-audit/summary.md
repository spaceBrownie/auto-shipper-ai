# FR-024: Codebase Quality Audit — Summary

## Feature Summary

One-time compliance audit bringing all pre-existing code into alignment with CLAUDE.md engineering constraints added after PM postmortems PM-001 through PM-012. Fixes 1 cross-module listener transaction violation, 12 missing `@Value` defaults, 2 URL-encoding vulnerabilities, adds ArchUnit Rule 4 for structural enforcement, adds post-persist verification logging, documents mockk as the recommended path forward for value class testing, and codifies the vibe coding anti-pattern warning.

## Changes Made

### BR-1: VendorSlaBreachRefunder Transaction Fix
Fixed the last remaining cross-module `@EventListener` violation. `VendorSlaBreachRefunder` in the fulfillment module now uses `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)` instead of plain `@EventListener` + `@Transactional`. This prevents phantom refunds if the vendor module's publishing transaction rolls back.

### BR-2: @Value Empty Defaults + Blank-Credential Guards
Added `${key:}` empty-default syntax to 12 `@Value` annotations across 6 adapter files. Each adapter now guards against blank credentials at its entry point — carrier adapters throw `ProviderUnavailableException`, `StripeRefundAdapter` throws `IllegalStateException`. This prevents profile-dependent startup crashes and silent zero-cost envelope corruption.

### BR-3: URL-Encode OAuth Token Bodies
Applied `URLEncoder.encode(value, StandardCharsets.UTF_8)` to credential values in `FedExRateAdapter.fetchBearerToken()` and `AmazonCreatorsApiAdapter.getAccessToken()`. Prevents parameter injection via special characters in credential values.

### BR-4: ArchUnit Rule 4
Added structural enforcement rule that flags any `@EventListener` method handling events from `com.autoshipper.shared.events.*`. `PricingEngine.onPricingSignal` is allowlisted as a tracked exception. This closes the enforcement gap that allowed BR-1's violation to exist undetected.

### BR-5: PricingInitializer Post-Persist Log
Added post-persist verification in `PricingInitializer`: after `setInitialPrice()`, reads back from `SkuPriceRepository` and logs at INFO (success with price/margin values) or ERROR (entity not found). Provides early warning if writes are silently dropped — the original PM-001 bug site.

### BR-6: mockk Investigation
Documented the Mockito limitation with Kotlin `@JvmInline value class` parameters (NPE on `any<SkuId>()`). mockk resolves this natively. Recommendation: gradual migration — new tests use mockk, existing files migrated opportunistically. Full migration estimated at 2-3 days across 52 files.

### BR-7: CLAUDE.md Constraint #17
Added vibe coding anti-pattern warning to Critical Engineering Constraints referencing PM-008 and PM-010. Directs implementers to the 6-phase feature-request workflow.

## Files Modified

| File | Module | Change |
|---|---|---|
| `modules/fulfillment/src/main/kotlin/.../VendorSlaBreachRefunder.kt` | fulfillment | BR-1: Annotation fix |
| `modules/catalog/src/main/kotlin/.../ExternalApiConfig.kt` | catalog | BR-2: 5 `@Value` defaults + guards |
| `modules/catalog/src/main/kotlin/.../UpsRateAdapter.kt` | catalog | BR-2: 2 `@Value` defaults + guard |
| `modules/catalog/src/main/kotlin/.../FedExRateAdapter.kt` | catalog | BR-2 + BR-3: 2 `@Value` defaults + guard + URL encoding |
| `modules/catalog/src/main/kotlin/.../UspsRateAdapter.kt` | catalog | BR-2: 1 `@Value` default + guard |
| `modules/catalog/src/main/kotlin/.../StripeProcessingFeeProvider.kt` | catalog | BR-2: 1 `@Value` default + guard |
| `modules/fulfillment/src/main/kotlin/.../StripeRefundAdapter.kt` | fulfillment | BR-2: 1 `@Value` default + guard |
| `modules/portfolio/src/main/kotlin/.../AmazonCreatorsApiAdapter.kt` | portfolio | BR-3: URL encoding |
| `modules/pricing/src/main/kotlin/.../PricingInitializer.kt` | pricing | BR-5: Post-persist log |
| `modules/app/src/test/kotlin/.../ArchitectureTest.kt` | app | BR-4: Rule 4 (added in Phase 4) |
| `CLAUDE.md` | root | BR-7: Constraint #17 |

## New Files

| File | Purpose |
|---|---|
| `feature-requests/FR-024-codebase-quality-audit/mockk-investigation.md` | BR-6: mockk investigation and recommendation |
| `modules/catalog/src/test/kotlin/.../UpsRateAdapterBlankCredentialTest.kt` | BR-2: 3 tests |
| `modules/catalog/src/test/kotlin/.../FedExRateAdapterBlankCredentialTest.kt` | BR-2: 3 tests |
| `modules/catalog/src/test/kotlin/.../UspsRateAdapterBlankCredentialTest.kt` | BR-2: 1 test |
| `modules/catalog/src/test/kotlin/.../StripeProcessingFeeProviderBlankCredentialTest.kt` | BR-2: 1 test |
| `modules/fulfillment/src/test/kotlin/.../StripeRefundAdapterBlankCredentialTest.kt` | BR-2: 1 test |
| `modules/catalog/src/test/kotlin/.../FedExRateAdapterUrlEncodingTest.kt` | BR-3: 2 tests |
| `modules/portfolio/src/test/kotlin/.../AmazonCreatorsApiAdapterUrlEncodingTest.kt` | BR-3: 4 tests |

## Testing Completed

- **`./gradlew build`**: BUILD SUCCESSFUL — all tests pass, zero failures
- **New tests**: 16 tests across 8 files (all green)
- **Existing tests**: All unchanged tests continue to pass
- **ArchUnit**: 4 rules (3 existing + 1 new), all passing

## Deployment Notes

- No database migrations required
- No new dependencies added
- No configuration changes needed (the `@Value` defaults mean existing configs are unaffected)
- All changes are backward-compatible — no behavioral changes to business logic
- Each BR's changes are independently revertable
