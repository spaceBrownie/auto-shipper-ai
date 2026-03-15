> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/portfolio/src/...` instead of `portfolio/src/...`.

# FR-010: Portfolio Orchestration — Implementation Plan

## Technical Design

The `portfolio` module manages `Experiment` aggregates and provides the portfolio-level view across all SKUs. The `KillWindowMonitor` identifies SKUs past their kill window. The `CapitalReallocator` computes risk-adjusted returns and suggests reallocation. All reallocation decisions are advisory in Phase 1 — no automated fund transfers.

```
portfolio/src/main/kotlin/com/autoshipper/portfolio/
├── domain/
│   ├── Experiment.kt              (aggregate: budget, window, status)
│   ├── ExperimentStatus.kt        (enum)
│   └── KillRecommendation.kt      (advisory record: skuId, daysNegative, avgNetMargin)
├── domain/service/
│   ├── ExperimentService.kt       (CRUD + validation)
│   ├── KillWindowMonitor.kt       (@Scheduled daily)
│   ├── ScalingFlagService.kt      (marks high-performing SKUs for human review)
│   ├── CapitalReallocator.kt      (risk-adjusted return scoring)
│   ├── PortfolioReporter.kt       (KPI aggregation)
│   ├── MarginSignalProvider.kt    (interface — cross-module boundary)
│   └── JpaMarginSignalProvider.kt (native query impl against margin_snapshots)
├── handler/
│   └── PortfolioController.kt
└── config/
    └── PortfolioConfig.kt         (kill window, cache TTL, auto-terminate flag)
```

## Architecture Decisions

- **`Experiment` is separate from `Sku`**: An experiment is a hypothesis + budget + window. It gates the creation of a SKU — a SKU is only created after an experiment passes validation. This keeps the catalog clean and free of experiment metadata.
- **`KillWindowMonitor` reads from `capital` module's snapshots via interface pattern**: Follows the established `ActiveSkuProvider`/`JpaActiveSkuProvider` pattern from FR-009. A `MarginSignalProvider` interface (owned by the `portfolio` module) with a `JpaMarginSignalProvider` native-query implementation reads from `margin_snapshots` directly — no SQL view, no cross-module Kotlin dependency. This is consistent with how `capital` reads from `skus` and `orders` tables. The SQL view approach was considered but rejected to match the established pattern (see PM-002 for historical context on why SQL views were avoided for cross-module reads).
- **KillWindowMonitor termination is feature-flagged** (`portfolio.auto-terminate.enabled`, default `false`): See full decision breakdown below.
- **Capital reallocation is advisory in Phase 1**: `CapitalReallocator` produces a `ReallocationRecommendation` that is surfaced in the dashboard for human approval. No automatic fund movement.
- **Portfolio KPIs cached with Caffeine (5-minute TTL)**: These are read-heavy, write-rarely aggregates. Caching prevents repeated expensive aggregation queries.
- **`capital_reallocation_log` is append-only**: Reallocation recommendations are logged as advisory records. No fund movement in Phase 1. The table records freedCapital, recommendedTarget, and timestamp for audit trail.
- **`DemandScanJob` is deferred**: The solo-operator spec assigns `DemandScanJob` (Google Trends RSS + Reddit + Amazon PA-API) to the `portfolio` module. It is explicitly out of scope for this FR — demand scanning requires external API adapters that warrant their own FR. This FR covers experiment lifecycle and capital reallocation only.

## KillWindowMonitor — Termination Recommendation Decision

### Context

The capital module (FR-009) already handles **short-term** kill signals via `ShutdownRuleEngine`:
- Net margin < 30% for 7+ consecutive days → **PAUSE**
- Refund/chargeback rate breach → **PAUSE**

`KillWindowMonitor` handles the **long-term** case: a SKU that has been paused or sustained-negative for > `killWindowDays` (default 30) without recovering. This is a more consequential, irreversible action — termination — and warrants a deliberate design choice.

### Options Considered

| Option | Behaviour | Tradeoff |
|--------|-----------|----------|
| **A — Fully automated** | KillWindowMonitor emits `KillWindowBreached` event; `CatalogKillWindowListener` auto-terminates | Maximum automation, matches solo-operator goal. Risk: false positives permanently kill recovering SKUs. |
| **B — Advisory only** | Writes `KillRecommendation` to DB; dashboard shows card for human action | Safe but requires daily review — defeats the "zero daily intervention" goal over time. |
| **C — Feature-flagged hybrid** ✅ | Flag off: writes recommendation only. Flag on: also emits `KillWindowBreached` → auto-terminate. Dashboard always visible. | Start safe, enable automation once trusted. No code rework to upgrade. |

### Decision: Option C — Feature-Flagged Hybrid

**`portfolio.auto-terminate.enabled=false` by default.**

**Flag OFF (default):**
- `KillWindowMonitor` finds qualifying SKUs, writes a `KillRecommendation` record (skuId, daysNegative, avgNetMargin, detectedAt) to `kill_recommendations` table
- Dashboard "Kill Log" view surfaces these for one-click manual confirmation
- `POST /api/portfolio/kill-recommendations/{id}/confirm` → transitions SKU to Terminated via catalog API
- No domain event emitted; no listener needed

**Flag ON (`portfolio.auto-terminate.enabled=true`):**
- Same recommendation write (audit trail preserved)
- Additionally emits `KillWindowBreached(skuId, daysNegative, avgNetMargin)` domain event via `ApplicationEventPublisher`
- `CatalogKillWindowListener` in catalog module listens — **MANDATORY: use PM-005 double-annotation pattern**:
  ```kotlin
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun onKillWindowBreached(event: KillWindowBreached) {
      skuService.terminate(event.skuId, TerminationReason.MARGIN_BELOW_FLOOR)
  }
  ```
- `KillWindowBreached` event must be added to `modules/shared/src/main/kotlin/com/autoshipper/shared/events/`

**Why this split between capital and portfolio:**
- Capital: *immediate* threshold breach → pause (reversible; short-term signal)
- Portfolio: *sustained* underperformance past kill window → terminate (permanent; long-term verdict)
- These are complementary, not duplicate. The capital pause is the first gate; the kill window is the final exit.

## Layer-by-Layer Implementation

### Domain Layer
- `Experiment`: id (`ExperimentId`), name, hypothesisDescription, budget (`Money`), validationWindowDays, status, createdAt, launchedSkuId (`SkuId?`)
- `ExperimentStatus`: ACTIVE, VALIDATED, FAILED, LAUNCHED, TERMINATED
- `KillRecommendation`: id, skuId (`SkuId`), daysNegative, avgNetMargin (`BigDecimal`), detectedAt, confirmedAt (nullable), confirmedBy

### Domain Service
- `ExperimentService.create(request)`: creates experiment in ACTIVE status
- `ExperimentService.markValidated(experimentId: ExperimentId, skuId: SkuId)`: transitions to VALIDATED, links to SKU
- `KillWindowMonitor.scan()`: finds SKUs with sustained negative signals past kill window; writes `KillRecommendation`; if `autoTerminateEnabled`, emits `KillWindowBreached`
- `ScalingFlagService.scan()`: identifies SKUs with ≥3 consecutive margin snapshots above 50% net margin and revenue growth; writes flag for dashboard review (no auto-action)
- `CapitalReallocator.recommend()`: scores all active SKUs by risk-adjusted return, returns ranked `ReallocationRecommendation`
- `PortfolioReporter.summary()`: counts, blended margin, capital deployed — cached

### Handler Layer
- `GET /api/portfolio/summary`, `GET /api/portfolio/experiments`, `POST /api/portfolio/experiments`
- `GET /api/portfolio/kill-recommendations` (pending), `POST /api/portfolio/kill-recommendations/{id}/confirm` (manual confirmation when flag off)

## Task Breakdown

### Shared Module (prerequisite)
- [x] Add `KillWindowBreached` event to `modules/shared/src/main/kotlin/com/autoshipper/shared/events/` (skuId: SkuId, daysNegative: Int, avgNetMargin: BigDecimal, occurredAt: Instant) — only emitted when `portfolio.auto-terminate.enabled=true`

### Catalog Module (prerequisite — only needed when flag enabled)
- [x] Implement `CatalogKillWindowListener` in `modules/catalog` — **MANDATORY: PM-005 double-annotation pattern**:
  ```kotlin
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun onKillWindowBreached(event: KillWindowBreached) {
      skuService.terminate(event.skuId, TerminationReason.MARGIN_BELOW_FLOOR)
  }
  ```

### Domain Layer
- [x] Implement `ExperimentStatus` enum with 5 statuses
- [x] Implement `Experiment` JPA entity (id: UUID mapped from `ExperimentId`, name, hypothesis, budgetAmount, budgetCurrency, validationWindowDays, status, launchedSkuId)
  - Use `ExperimentId` value class in service/domain layer; map to UUID column via JPA `@Column`
- [x] Implement `KillRecommendation` JPA entity (id, skuId, daysNegative, avgNetMargin, detectedAt, confirmedAt nullable)
- [x] Implement `ReallocationRecommendation` data class (rankedSkus with risk-adjusted return scores, freedCapital, recommendedTarget)
- [x] Define `PortfolioConfig` `@ConfigurationProperties` (killWindowDays=30, kpiCacheTtlMinutes=5, autoTerminateEnabled=false)

### Domain Service
- [x] Implement `ExperimentService.create(name, hypothesis, budget: Money, windowDays)`
- [x] Implement `ExperimentService.markValidated(experimentId: ExperimentId, skuId: SkuId)` — links experiment to launched SKU
- [x] Implement `ExperimentService.markFailed(experimentId: ExperimentId)` — transitions to FAILED
- [x] Implement `MarginSignalProvider` interface: `getSkusWithNegativeMarginSince(days: Int): List<SkuId>` and `getAverageNetMargin(skuId: SkuId): BigDecimal`
- [x] Implement `JpaMarginSignalProvider` with `@PersistenceContext EntityManager` native queries against `margin_snapshots` table (same pattern as `JpaActiveSkuProvider` in capital — no cross-module Kotlin dependency)
- [x] Implement `KillWindowMonitor` `@Scheduled(cron = "0 0 1 * * *")` (1am daily):
  - Use `MarginSignalProvider` to find SKUs with net_margin < 0 for > killWindowDays consecutive days
  - Write `KillRecommendation` record for every qualifying SKU (always — audit trail regardless of flag)
  - If `portfolioConfig.autoTerminateEnabled`: publish `KillWindowBreached` via `ApplicationEventPublisher`
- [x] Implement `ScalingFlagService` — identifies SKUs meeting scaling criteria (≥3 consecutive snapshots with net margin ≥ 50%); writes flag to `scaling_flags` table for dashboard review (no auto-action in Phase 1)
- [x] Implement `CapitalReallocator.recommend()` — score active SKUs by (avg net margin × revenue volume / risk factor)
- [x] Implement `PortfolioReporter.summary()` with Caffeine cache (5-minute TTL)
- [x] Summary includes: totalExperiments, activeSkus, terminatedSkus, blendedMargin, capitalDeployed

### Handler Layer
- [x] Implement `GET /api/portfolio/summary` returning `PortfolioSummaryResponse`
- [x] Implement `GET /api/portfolio/experiments` returning paginated experiment list
- [x] Implement `POST /api/portfolio/experiments` creating new experiment
- [x] Implement `GET /api/portfolio/reallocation` returning `ReallocationRecommendation`
- [x] Implement `GET /api/portfolio/kill-recommendations` returning pending `KillRecommendation` records
- [x] Implement `POST /api/portfolio/kill-recommendations/{id}/confirm` — manually confirms termination; calls catalog state transition API
- [x] Add request/response DTOs for all endpoints

### Config Layer
- [x] Configure Caffeine cache bean for portfolio KPIs in `PortfolioConfig`
- [x] Add `spring.cache.type=caffeine` and TTL settings to `application.yml`
- [x] Add `portfolio.auto-terminate.enabled=false` to `application.yml` with comment explaining the flag

### Persistence (Common Layer)
- [x] Write `V14__portfolio.sql` migration:
  - `experiments`: id, name, hypothesis_description, budget_amount, budget_currency, validation_window_days, status, launched_sku_id, created_at
  - `kill_recommendations`: id, sku_id, days_negative, avg_net_margin, detected_at, confirmed_at (nullable)
  - `capital_reallocation_log`: id, freed_capital_amount, freed_capital_currency, recommended_target_sku_id, recommended_at (advisory log — no fund transfers in Phase 1)
  - `scaling_flags`: id, sku_id, flagged_at, resolved_at (nullable)
- [x] Implement `ExperimentRepository`
- [x] Implement `KillRecommendationRepository`

## Testing Strategy

- Unit test `KillWindowMonitor` — flag OFF: SKU with 31-day negative signal → `KillRecommendation` written, no event emitted; 29-day → no action
- Unit test `KillWindowMonitor` — flag ON: qualifying SKU → recommendation written AND `KillWindowBreached` event published
- Unit test `CatalogKillWindowListener`: `KillWindowBreached` event → SKU terminated with `MARGIN_BELOW_FLOOR`; verify `REQUIRES_NEW` propagation via annotation inspection (same pattern as `PricingDecisionListenerTest`)
- Unit test `ScalingFlagService`: SKU with 3 consecutive high-margin snapshots → flag written; 2 consecutive → no flag
- Unit test `CapitalReallocator`: known SKU metrics → correct risk-adjusted ranking
- Unit test `PortfolioReporter`: correct aggregate counts and blended margin
- Integration test: experiment created → SKU launched from experiment → experiment status LAUNCHED
- Integration test (flag ON): `KillWindowBreached` event → `CatalogKillWindowListener` terminates SKU; verify `AFTER_COMMIT` + `REQUIRES_NEW` isolation (recommendation record survives if listener throws — tests PM-005 pattern)
- Cache test: second call to `summary()` returns cached result without DB query

## Rollout Plan

1. Add `KillWindowBreached` event to `shared` module
2. Write `V14__portfolio.sql`
3. Implement `Experiment` entity and `ExperimentService`
4. Implement `MarginSignalProvider` / `JpaMarginSignalProvider`
5. Implement `KillWindowMonitor` (flag-aware)
6. Implement `CatalogKillWindowListener` in catalog module
7. Implement `ScalingFlagService`
8. Implement `CapitalReallocator`
9. Implement `PortfolioReporter` with cache
10. Add REST handler (including kill-recommendations endpoints)
