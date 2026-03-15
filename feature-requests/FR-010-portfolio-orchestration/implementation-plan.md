> **Path update (FR-013):** All source paths below use the post-refactor `modules/` prefix,
> e.g. `modules/portfolio/src/...` instead of `portfolio/src/...`.

# FR-010: Portfolio Orchestration — Implementation Plan

## Technical Design

The `portfolio` module manages `Experiment` aggregates and provides the portfolio-level view across all SKUs. An experiment is a **listing hypothesis** — "list this product and see if it sells" — with zero upfront capital. The customer's payment covers all costs; the remainder is profit. The `KillWindowMonitor` identifies SKUs past their kill window. The `PriorityRanker` ranks SKUs by risk-adjusted return to determine which candidates deserve listing priority next. All ranking decisions are advisory in Phase 1.

```
portfolio/src/main/kotlin/com/autoshipper/portfolio/
├── domain/
│   ├── Experiment.kt              (aggregate: hypothesis, window, status)
│   ├── ExperimentStatus.kt        (enum)
│   └── KillRecommendation.kt      (advisory record: skuId, daysNegative, avgNetMargin)
├── domain/service/
│   ├── ExperimentService.kt       (CRUD + validation)
│   ├── KillWindowMonitor.kt       (@Scheduled daily)
│   ├── ScalingFlagService.kt      (marks high-performing SKUs for human review)
│   ├── PriorityRanker.kt          (risk-adjusted return scoring for listing priority)
│   ├── RefundPatternAnalyzer.kt   (portfolio-wide refund trend detection)
│   ├── PortfolioReporter.kt       (KPI aggregation)
│   ├── MarginSignalProvider.kt    (interface — cross-module boundary)
│   └── JpaMarginSignalProvider.kt (native query impl against margin_snapshots)
├── handler/
│   └── PortfolioController.kt
└── config/
    └── PortfolioConfig.kt         (kill window, cache TTL, auto-terminate flag)
```

## Architecture Decisions

- **`Experiment` is separate from `Sku`**: An experiment is a listing hypothesis + validation window. It gates the creation of a SKU — a SKU is only created after an experiment passes validation. There is no upfront budget; the system invests only compute time to discover and validate. This keeps the catalog clean and free of experiment metadata.
- **`KillWindowMonitor` reads from `capital` module's snapshots via interface pattern**: Follows the established `ActiveSkuProvider`/`JpaActiveSkuProvider` pattern from FR-009. A `MarginSignalProvider` interface (owned by the `portfolio` module) with a `JpaMarginSignalProvider` native-query implementation reads from `margin_snapshots` directly — no SQL view, no cross-module Kotlin dependency. This is consistent with how `capital` reads from `skus` and `orders` tables. The SQL view approach was considered but rejected to match the established pattern (see PM-002 for historical context on why SQL views were avoided for cross-module reads).
- **KillWindowMonitor termination is feature-flagged** (`portfolio.auto-terminate.enabled`, default `false`): See full decision breakdown below.
- **Priority ranking is advisory in Phase 1**: `PriorityRanker` produces a `PriorityRanking` that ranks candidates and active SKUs by risk-adjusted return. Surfaced in dashboard. In Phase 1, the operator confirms which candidates to list next. In Phase 2, the system auto-lists the top-ranked candidates.
- **Portfolio KPIs cached with Caffeine (5-minute TTL)**: These are read-heavy, write-rarely aggregates. Caching prevents repeated expensive aggregation queries.
- **`priority_ranking_log` is append-only**: Rankings are logged for audit trail. The table records skuId, score, rank, and timestamp. No fund movement — the system is zero-capital; the customer's payment covers all costs.
- **Systemic refund analysis**: Per-SKU refund kill rules (>5% → pause) exist in the capital module, but they don't detect portfolio-wide patterns. If multiple SKUs spike refunds simultaneously, the root cause is systemic (listing quality, shipping partner, storefront UX) not product-specific. `RefundPatternAnalyzer` monitors cross-SKU refund trends and flags systemic issues before they destroy customer trust. Refund patterns also feed back into discovery — categories or suppliers that consistently generate refunds are blacklisted.
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
- `Experiment`: id (`ExperimentId`), name, hypothesisDescription, sourceSignal (what triggered this experiment — trend data, demand signal, etc.), estimatedMarginPerUnit (`Money?`, projected from discovery scoring), validationWindowDays, status, createdAt, launchedSkuId (`SkuId?`)
- `ExperimentStatus`: ACTIVE, VALIDATED, FAILED, LAUNCHED, TERMINATED
- `KillRecommendation`: id, skuId (`SkuId`), daysNegative, avgNetMargin (`BigDecimal`), detectedAt, confirmedAt (nullable), confirmedBy
- `PriorityRanking`: data class — rankedSkus (list of skuId + score + rank), rankedAt

### Domain Service
- `ExperimentService.create(request)`: creates experiment in ACTIVE status — no budget field, just hypothesis + source signal + validation window
- `ExperimentService.markValidated(experimentId: ExperimentId, skuId: SkuId)`: transitions to VALIDATED, links to SKU
- `KillWindowMonitor.scan()`: finds SKUs with sustained negative signals past kill window; writes `KillRecommendation`; if `autoTerminateEnabled`, emits `KillWindowBreached`
- `ScalingFlagService.scan()`: identifies SKUs with ≥3 consecutive margin snapshots above 50% net margin and revenue growth; writes flag for dashboard review (no auto-action)
- `PriorityRanker.rank()`: scores all active SKUs and pending candidates by risk-adjusted return (avg net margin × revenue volume / risk factor), returns ranked `PriorityRanking` — determines which candidates should be listed next and which active SKUs deserve increased visibility
- `RefundPatternAnalyzer.analyze()`: monitors refund rates across the entire portfolio; flags when N+ SKUs spike refunds simultaneously (configurable threshold, default 3+ SKUs with >3% refund rate in same 7-day window); categorizes root cause (listing accuracy, shipping delays, product quality, price competitiveness); feeds blacklist data back to discovery engine
- `PortfolioReporter.summary()`: counts, blended margin, total profit — cached

### Handler Layer
- `GET /api/portfolio/summary`, `GET /api/portfolio/experiments`, `POST /api/portfolio/experiments`
- `GET /api/portfolio/kill-recommendations` (pending), `POST /api/portfolio/kill-recommendations/{id}/confirm` (manual confirmation when flag off)

## Task Breakdown

### Shared Module (prerequisite)
- [ ] Add `KillWindowBreached` event to `modules/shared/src/main/kotlin/com/autoshipper/shared/events/` (skuId: SkuId, daysNegative: Int, avgNetMargin: BigDecimal, occurredAt: Instant) — only emitted when `portfolio.auto-terminate.enabled=true`

### Catalog Module (prerequisite — only needed when flag enabled)
- [ ] Implement `CatalogKillWindowListener` in `modules/catalog` — **MANDATORY: PM-005 double-annotation pattern**:
  ```kotlin
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun onKillWindowBreached(event: KillWindowBreached) {
      skuService.terminate(event.skuId, TerminationReason.MARGIN_BELOW_FLOOR)
  }
  ```

### Domain Layer
- [ ] Implement `ExperimentStatus` enum with 5 statuses
- [ ] Implement `Experiment` JPA entity (id: UUID mapped from `ExperimentId`, name, hypothesis, sourceSignal, estimatedMarginPerUnit (nullable), validationWindowDays, status, launchedSkuId)
  - Use `ExperimentId` value class in service/domain layer; map to UUID column via JPA `@Column`
  - No budget field — zero-capital model; the customer's payment covers all costs
- [ ] Implement `KillRecommendation` JPA entity (id, skuId, daysNegative, avgNetMargin, detectedAt, confirmedAt nullable)
- [ ] Implement `PriorityRanking` data class (rankedSkus with risk-adjusted return scores, rankedAt)
- [ ] Define `PortfolioConfig` `@ConfigurationProperties` (killWindowDays=30, kpiCacheTtlMinutes=5, autoTerminateEnabled=false)

### Domain Service
- [ ] Implement `ExperimentService.create(name, hypothesis, sourceSignal, windowDays, estimatedMarginPerUnit: Money?)`
- [ ] Implement `ExperimentService.markValidated(experimentId: ExperimentId, skuId: SkuId)` — links experiment to launched SKU
- [ ] Implement `ExperimentService.markFailed(experimentId: ExperimentId)` — transitions to FAILED
- [ ] Implement `MarginSignalProvider` interface: `getSkusWithNegativeMarginSince(days: Int): List<SkuId>` and `getAverageNetMargin(skuId: SkuId): BigDecimal`
- [ ] Implement `JpaMarginSignalProvider` with `@PersistenceContext EntityManager` native queries against `margin_snapshots` table (same pattern as `JpaActiveSkuProvider` in capital — no cross-module Kotlin dependency)
- [ ] Implement `KillWindowMonitor` `@Scheduled(cron = "0 0 1 * * *")` (1am daily):
  - Use `MarginSignalProvider` to find SKUs with net_margin < 0 for > killWindowDays consecutive days
  - Write `KillRecommendation` record for every qualifying SKU (always — audit trail regardless of flag)
  - If `portfolioConfig.autoTerminateEnabled`: publish `KillWindowBreached` via `ApplicationEventPublisher`
- [ ] Implement `ScalingFlagService` — identifies SKUs meeting scaling criteria (≥3 consecutive snapshots with net margin ≥ 50%); writes flag to `scaling_flags` table for dashboard review (no auto-action in Phase 1)
- [ ] Implement `PriorityRanker.rank()` — score active SKUs and pending candidates by (avg net margin × revenue volume / risk factor); determines listing priority
- [ ] Implement `RefundPatternAnalyzer.analyze()` — cross-SKU refund trend detection; flag systemic issues when N+ SKUs spike simultaneously; categorize root cause; maintain category/supplier blacklist for discovery feedback
- [ ] Implement `PortfolioReporter.summary()` with Caffeine cache (5-minute TTL)
- [ ] Summary includes: totalExperiments, activeSkus, terminatedSkus, blendedMargin, totalProfit, systemicRefundAlerts

### Handler Layer
- [ ] Implement `GET /api/portfolio/summary` returning `PortfolioSummaryResponse`
- [ ] Implement `GET /api/portfolio/experiments` returning paginated experiment list
- [ ] Implement `POST /api/portfolio/experiments` creating new experiment
- [ ] Implement `GET /api/portfolio/priority-ranking` returning `PriorityRanking`
- [ ] Implement `GET /api/portfolio/refund-alerts` returning systemic refund alerts
- [ ] Implement `GET /api/portfolio/kill-recommendations` returning pending `KillRecommendation` records
- [ ] Implement `POST /api/portfolio/kill-recommendations/{id}/confirm` — manually confirms termination; calls catalog state transition API
- [ ] Add request/response DTOs for all endpoints

### Config Layer
- [ ] Configure Caffeine cache bean for portfolio KPIs in `PortfolioConfig`
- [ ] Add `spring.cache.type=caffeine` and TTL settings to `application.yml`
- [ ] Add `portfolio.auto-terminate.enabled=false` to `application.yml` with comment explaining the flag

### Persistence (Common Layer)
- [ ] Write `V14__portfolio.sql` migration:
  - `experiments`: id, name, hypothesis_description, source_signal, estimated_margin_per_unit (nullable), estimated_margin_currency (nullable), validation_window_days, status, launched_sku_id, created_at
  - `kill_recommendations`: id, sku_id, days_negative, avg_net_margin, detected_at, confirmed_at (nullable)
  - `priority_ranking_log`: id, sku_id, score, rank, ranked_at (audit trail for ranking decisions)
  - `scaling_flags`: id, sku_id, flagged_at, resolved_at (nullable)
  - `refund_alerts`: id, alert_type (SYSTEMIC, CATEGORY, SUPPLIER), affected_sku_ids (text/json), root_cause_category, refund_rate_avg, detected_at, resolved_at (nullable)
  - `discovery_blacklist`: id, entity_type (CATEGORY, SUPPLIER), entity_id, reason, blacklisted_at, expires_at (nullable)
- [ ] Implement `ExperimentRepository`
- [ ] Implement `KillRecommendationRepository`

## Testing Strategy

- Unit test `KillWindowMonitor` — flag OFF: SKU with 31-day negative signal → `KillRecommendation` written, no event emitted; 29-day → no action
- Unit test `KillWindowMonitor` — flag ON: qualifying SKU → recommendation written AND `KillWindowBreached` event published
- Unit test `CatalogKillWindowListener`: `KillWindowBreached` event → SKU terminated with `MARGIN_BELOW_FLOOR`; verify `REQUIRES_NEW` propagation via annotation inspection (same pattern as `PricingDecisionListenerTest`)
- Unit test `ScalingFlagService`: SKU with 3 consecutive high-margin snapshots → flag written; 2 consecutive → no flag
- Unit test `PriorityRanker`: known SKU metrics → correct risk-adjusted ranking
- Unit test `RefundPatternAnalyzer`: 3+ SKUs with >3% refund rate in same window → systemic alert; 1 SKU with high refund rate → no systemic alert (per-SKU kill rule handles it)
- Unit test `RefundPatternAnalyzer`: systemic alert → category added to discovery blacklist
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
8. Implement `PriorityRanker`
9. Implement `RefundPatternAnalyzer`
10. Implement `PortfolioReporter` with cache
11. Add REST handler (including kill-recommendations and refund-alerts endpoints)
