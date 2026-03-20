# FR-019: PM-012 Prevention Constraints — Summary

**Linear issue:** RAT-23
**Branch:** `feat/RAT-23-pm012-prevention-constraints`
**Completed:** 2026-03-20

## Feature Summary

Implemented all 6 prevention items from PM-012 to structurally prevent the three bug classes (Kotlin internal constructor on Spring beans, @Value without defaults, Jackson path() vs get()) from recurring. Added CLAUDE.md constraints #13-#15, fixed existing adapter bugs, improved CI test output, and added a portfolio module @SpringBootTest smoke test.

## Changes Made

- **CLAUDE.md constraints #13-#15** — codified @Value defaults, internal constructor prohibition, and Jackson get() vs path() rules
- **AmazonCreatorsApiAdapter** — added @Value defaults, `get()` fix for token extraction, blank credential guard, lazy restClient
- **CjDropshippingAdapter** — added @Value defaults, blank credential guard, lazy restClient
- **Gradle testLogging** — `exceptionFormat = FULL` in root build.gradle.kts so all modules print full exception chains
- **CI artifact upload** — HTML test reports uploaded on failure with 7-day retention
- **PortfolioContextLoadTest** — @SpringBootTest smoke test with 7 @MockBean repos, validates bean wiring without database

## Files Modified

| File | Change |
|------|--------|
| `CLAUDE.md` | Added constraints #13, #14, #15 |
| `modules/portfolio/.../proxy/AmazonCreatorsApiAdapter.kt` | @Value defaults, get() fix, blank guard, lazy restClient |
| `modules/portfolio/.../proxy/CjDropshippingAdapter.kt` | @Value defaults, blank guard, lazy restClient |
| `build.gradle.kts` | Added testLogging block (exceptionFormat FULL) |
| `.github/workflows/ci.yml` | Added HTML test report artifact upload step |
| `modules/portfolio/.../PortfolioContextLoadTest.kt` | **New** — @SpringBootTest smoke test |
| `modules/portfolio/.../TestPortfolioApplication.kt` | **New** — test application class for portfolio module |
| `modules/portfolio/src/test/resources/application-test.yml` | **New** — test profile config (no DB) |
| `feature-requests/FR-019-.../implementation-plan.md` | All checkboxes checked |

## Testing Completed

- All portfolio tests pass (78 total, 1 new PortfolioContextLoadTest)
- All module tests pass (`./gradlew test -x :app:test`)
- `AutoShipperApplicationTest.contextLoads()` passes
- No regressions

## Deployment Notes

- No database migration
- No runtime behavior change (NFR-1) — only failure modes change (graceful early return instead of crash)
- CI will now produce full exception output and upload HTML test reports on failure
- Safe to merge directly
