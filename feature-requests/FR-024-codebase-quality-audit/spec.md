# FR-024: Codebase Quality Audit

## Problem Statement

Over the course of building FR-001 through FR-023, the project has accumulated a series of postmortems (PM-001 through PM-015) documenting recurring bug classes. Each postmortem produced two outputs: (1) a fix for the specific instance found, and (2) a new CLAUDE.md engineering constraint to prevent recurrence. However, the constraints were added *after* the code that violates them was already written. No systematic sweep has been performed to bring pre-existing code into compliance with constraints added later.

The result is a codebase where newer modules follow all constraints while older modules still contain the exact patterns that the constraints were designed to prevent. This creates three problems:

1. **Runtime risk.** The `VendorSlaBreachRefunder` uses plain `@EventListener` for a cross-module write -- the same pattern that caused silent data loss in PM-001, PM-005, and PM-006. If the vendor module's publishing transaction fails after the fulfillment module has already issued refunds, those refunds cannot be rolled back.

2. **Profile-dependent startup crashes.** Twelve `@Value` annotations in the catalog and fulfillment modules lack empty defaults. Spring resolves `@Value` during constructor injection *before* evaluating `@ConditionalOnProperty`, so any profile that does not define these properties (local dev, CI, test) will crash the application context. This is the exact pattern documented in PM-012 that led to CLAUDE.md constraint #13.

3. **Parameter injection vulnerability.** Two adapters (`FedExRateAdapter`, `AmazonCreatorsApiAdapter`) construct OAuth token request bodies via raw string interpolation without URL-encoding user-supplied values. If credential values contain `&` or `=` characters, the request body is corrupted. Worse, a compromised credential store could inject arbitrary form parameters. This violates CLAUDE.md constraint #12 (added after PM-003 round 3).

4. **Incomplete structural enforcement.** The ArchUnit test suite (3 rules) catches `@TransactionalEventListener` misuse and `Persistable` violations, but does not catch plain `@EventListener` on cross-module writes -- the exact pattern in issue #1 above. The existing rules would not have prevented the `VendorSlaBreachRefunder` bug.

5. **Missing observability.** `PricingInitializer` is an `AFTER_COMMIT` + `REQUIRES_NEW` listener (the PM-001 pattern). It was the original site of the silent data loss bug. While the transaction fix was applied, no post-persist verification log was added, so a recurrence would again be invisible in logs.

This feature request is a one-time audit to bring all pre-existing code into compliance with all CLAUDE.md constraints and to close the remaining structural enforcement gaps.

## Business Requirements

### BR-1: Fix cross-module event listener transaction boundary violation

`VendorSlaBreachRefunder` in the fulfillment module listens to `VendorSlaBreached` (published by the vendor module) using plain `@EventListener` with `@Transactional`. This is a cross-module listener that writes to the database (issues refunds via `RefundProvider` and updates order state). Per CLAUDE.md constraint #6 and the pattern established by PM-001, PM-005, and PM-006, it must use `@TransactionalEventListener(phase = AFTER_COMMIT)` paired with `@Transactional(propagation = Propagation.REQUIRES_NEW)`.

### BR-2: Add empty defaults to all @Value annotations on adapter constructor parameters

Twelve `@Value` annotations across six files in the catalog and fulfillment modules lack the `${key:}` empty-default syntax required by CLAUDE.md constraint #13. The affected files and properties are:

- `ExternalApiConfig`: `ups.api.base-url`, `fedex.api.base-url`, `usps.api.base-url`, `stripe.api.base-url`, `shopify.api.base-url`
- `UpsRateAdapter`: `ups.api.client-id`, `ups.api.client-secret`
- `FedExRateAdapter`: `fedex.api.client-id`, `fedex.api.client-secret`
- `UspsRateAdapter`: `usps.api.oauth-token`
- `StripeProcessingFeeProvider`: `stripe.api.secret-key`
- `StripeRefundAdapter`: `stripe.api.secret-key`

Each adapter receiving a blank value must guard in its `fetch()` method (or equivalent entry point) with an early return that logs a warning and returns an empty result, per constraint #13.

### BR-3: URL-encode user-supplied values in OAuth token request bodies

Two adapters construct form-encoded request bodies using raw string interpolation, violating CLAUDE.md constraint #12:

- `FedExRateAdapter.fetchBearerToken()`: `"grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret"`
- `AmazonCreatorsApiAdapter.getAccessToken()`: same pattern with `credentialId` and `credentialSecret`

All user-supplied values in form-encoded request bodies must be URL-encoded using `URLEncoder.encode(value, StandardCharsets.UTF_8)`.

### BR-4: Add ArchUnit rule for cross-module @EventListener violations

The existing ArchUnit test suite (3 rules) does not detect cross-module `@EventListener` handlers that should be `@TransactionalEventListener`. A new rule must detect `@EventListener`-annotated methods in one module that handle event types defined in a different module and flag them as violations. This closes the structural enforcement gap that allowed BR-1's violation to exist undetected.

### BR-5: Add post-persist verification logging to PricingInitializer

`PricingInitializer` is the original site of the PM-001 silent data loss bug. While the `AFTER_COMMIT` + `REQUIRES_NEW` fix was applied, the listener does not log after successful persistence, making a recurrence invisible. Add a post-persist log statement at INFO level confirming the `SkuPriceEntity` was persisted, including the SKU ID, price, and margin values.

### BR-6: Investigate mockk as Mockito alternative for value class mocking

PM-008 documents that Mockito cannot mock or capture Kotlin inline value classes (e.g., `SkuId`), forcing tests to strip behavioral assertions and rely on weaker patterns. The project currently uses Mockito exclusively (268 imports across 52 test files). This requirement is to investigate whether mockk resolves the value class mocking limitation and to document findings with a recommendation. This is an investigation-only requirement -- no migration should occur as part of this audit.

### BR-7: Add CLAUDE.md reference to vibe coding anti-pattern warning

PM-008 and PM-010 document the "vibe coding" anti-pattern (single-session implementation without structured workflow, producing green builds with broken features). CLAUDE.md does not reference this risk. Add a concise warning to the Critical Engineering Constraints or Testing Conventions section of CLAUDE.md that references PM-008 and PM-010, directing implementers to use the feature-request workflow.

## Success Criteria

- [ ] `VendorSlaBreachRefunder.onVendorSlaBreached()` uses `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Transactional(propagation = Propagation.REQUIRES_NEW)` instead of `@EventListener` and `@Transactional`
- [ ] All existing `VendorSlaBreachRefunder` tests pass after the annotation change (updated to use `TransactionTemplate` if needed)
- [ ] All 12 `@Value` annotations in `ExternalApiConfig`, `UpsRateAdapter`, `FedExRateAdapter`, `UspsRateAdapter`, `StripeProcessingFeeProvider`, and `StripeRefundAdapter` use the `${key:}` empty-default syntax
- [ ] Each affected adapter guards against blank/empty injected values with early return, warning log, and empty result
- [ ] `FedExRateAdapter.fetchBearerToken()` URL-encodes `clientId` and `clientSecret` in the request body
- [ ] `AmazonCreatorsApiAdapter.getAccessToken()` URL-encodes `credentialId` and `credentialSecret` in the request body
- [ ] `ArchitectureTest` contains a new rule (Rule 4) that detects `@EventListener`-annotated methods handling cross-module events, and the rule passes after BR-1's fix
- [ ] `PricingInitializer` logs at INFO level after successful `SkuPriceEntity` persistence, including SKU ID, price, and margin
- [ ] A mockk investigation document exists in the feature request directory documenting findings and recommendation
- [ ] CLAUDE.md contains a warning about the vibe coding anti-pattern with references to PM-008 and PM-010
- [ ] `./gradlew build` passes with zero test failures
- [ ] No new test files are deleted or weakened -- all existing assertions are preserved or strengthened

## Non-Functional Requirements

### NFR-1: Zero regressions

All existing tests must continue to pass. No test may be deleted, weakened (e.g., replacing exact-value assertions with `any()` matchers), or skipped to accommodate the audit changes. If a test must be updated (e.g., to account for the `@EventListener` -> `@TransactionalEventListener` change in BR-1), the updated test must be at least as strict as the original.

### NFR-2: No behavioral changes

The audit must not change any business logic, domain rules, state machine transitions, or pricing calculations. The only changes permitted are: annotation corrections, URL-encoding additions, logging additions, ArchUnit rule additions, CLAUDE.md documentation additions, and test updates necessitated by annotation changes.

### NFR-3: Atomic per-module commits

Changes should be organized by module and concern so that each commit is independently reviewable. Mixing unrelated fixes in a single commit makes rollback difficult if one fix introduces a regression.

### NFR-4: Investigation deliverable format

The mockk investigation (BR-6) must be a markdown document in the feature request directory. It must include: (a) a reproduction of the specific Mockito limitation with `SkuId` value class, (b) whether mockk resolves it, (c) migration effort estimate, and (d) a clear recommend/defer/reject verdict.

## Dependencies

### Postmortem references

| Postmortem | Relevance |
|---|---|
| PM-001 | Established the `AFTER_COMMIT` + `REQUIRES_NEW` pattern; `PricingInitializer` was the original bug site (BR-5) |
| PM-003 | Round 3 identified the parameter injection vulnerability pattern now codified as constraint #12 (BR-3) |
| PM-005 | Documented cross-module listener transaction bugs in `ShutdownRuleListener`, `VendorBreachListener`, `PricingDecisionListener`; established the double-annotation fix pattern (BR-1) |
| PM-006 | Found `OrderEventListener` using the same `@EventListener` anti-pattern; reinforced constraint #6 (BR-1) |
| PM-008 | Documented vibe coding anti-pattern and Mockito value class mocking limitation (BR-6, BR-7) |
| PM-009 | Documented parallel implementation bugs, reinforced constraint #10 (context for audit thoroughness) |
| PM-012 | Documented `internal constructor` + Spring bean resolution failure; led to constraint #13 on `@Value` defaults (BR-2) |

### CLAUDE.md constraints addressed

| Constraint | Requirement |
|---|---|
| #6: Cross-module event listener transaction pattern | BR-1 |
| #12: URL-encode user-supplied values in form-encoded bodies | BR-3 |
| #13: @Value annotations must include empty defaults | BR-2 |

### Module dependencies

This audit touches files in: `catalog`, `fulfillment`, `portfolio`, `pricing`, and `app` (ArchUnit tests). No new module dependencies are introduced. No database migrations are required.
