# Feature Summary

FR-007 implements vendor governance with a pre-activation checklist, reliability scoring, scheduled SLA monitoring, and automated SKU auto-pause on vendor breach events.

## Changes Made

- **Vendor aggregate** with 5-item activation checklist (`@Embeddable`), status state machine (PENDING → ACTIVE → SUSPENDED), and soft-delete support
- **VendorActivationService** enforcing all 5 checklist items before activation
- **VendorReliabilityScorer** computing weighted score (40% on-time, 25% defect, 20% breach history, 15% response time)
- **VendorSlaMonitor** running every 15 minutes via `@Scheduled`, suspending vendors exceeding breach threshold and emitting `VendorSlaBreached` events
- **VendorBreachListener** in catalog module auto-pausing all linked SKUs (Listed/Scaled → Paused) on breach
- **REST API**: POST/GET vendors, PATCH checklist, POST activate, POST score
- **Flyway migration V9** creating `vendors`, `vendor_sku_assignments`, and `vendor_breach_log` tables

## Files Modified

### New Files — Vendor Module (`modules/vendor/src/main/kotlin/com/autoshipper/vendor/`)
- `domain/Vendor.kt` — JPA entity (aggregate root)
- `domain/VendorStatus.kt` — PENDING/ACTIVE/SUSPENDED enum
- `domain/VendorActivationChecklist.kt` — @Embeddable with 5 boolean fields
- `domain/VendorReliabilityScore.kt` — computed value data class
- `domain/VendorSkuAssignment.kt` — JPA entity linking vendors to SKUs
- `domain/VendorBreachLog.kt` — JPA entity for breach records
- `domain/VendorNotActivatedException.kt` — exception for incomplete checklist
- `domain/service/VendorActivationService.kt` — registration, checklist update, activation
- `domain/service/VendorReliabilityScorer.kt` — weighted reliability scoring
- `domain/service/VendorSlaMonitor.kt` — scheduled SLA breach detection
- `handler/VendorController.kt` — REST endpoints
- `handler/VendorExceptionHandler.kt` — @ControllerAdvice for vendor exceptions
- `handler/dto/RegisterVendorRequest.kt`
- `handler/dto/UpdateChecklistRequest.kt`
- `handler/dto/VendorResponse.kt`
- `handler/dto/VendorScoreResponse.kt`
- `handler/dto/ComputeScoreRequest.kt`
- `persistence/VendorRepository.kt`
- `persistence/VendorSkuAssignmentRepository.kt`
- `persistence/VendorBreachLogRepository.kt`

### New Files — Catalog Module
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/service/VendorBreachListener.kt`

### New Files — App Module
- `modules/app/src/main/resources/db/migration/V9__vendors.sql`

### Modified Files
- `modules/vendor/build.gradle.kts` — added JPA plugin and test dependencies

### Test Files
- `modules/vendor/src/test/kotlin/.../domain/VendorActivationChecklistTest.kt` — 3 unit tests
- `modules/vendor/src/test/kotlin/.../domain/VendorTest.kt` — 4 unit tests
- `modules/vendor/src/test/kotlin/.../domain/service/VendorActivationServiceTest.kt` — 4 unit tests
- `modules/vendor/src/test/kotlin/.../domain/service/VendorReliabilityScorerTest.kt` — 3 unit tests
- `modules/vendor/src/test/kotlin/.../domain/service/VendorSlaMonitorTest.kt` — 4 unit tests
- `modules/app/src/test/kotlin/.../vendor/VendorEndpointIntegrationTest.kt` — 7 E2E endpoint tests
- `modules/app/src/test/kotlin/.../vendor/VendorBreachIntegrationTest.kt` — 4 integration tests (breach → auto-pause)

## Testing Completed

- **18 unit tests** (vendor module): checklist validation, vendor lifecycle, activation service, reliability scoring, SLA monitor
- **11 integration tests** (app module): full endpoint E2E with Testcontainers/PostgreSQL, vendor breach → multi-SKU auto-pause
- All tests passing across all modules (`./gradlew test` — BUILD SUCCESSFUL)

## Deployment Notes

- Flyway migration `V9__vendors.sql` runs automatically on startup
- `@Scheduled` SLA monitor requires `@EnableScheduling` on the application class (already present via Spring Boot auto-config)
- No new external dependencies or environment variables required
