# FR-010: Portfolio Orchestration — Implementation Plan

## Technical Design

The `portfolio` module manages `Experiment` aggregates and provides the portfolio-level view across all SKUs. The `KillWindowMonitor` identifies SKUs past their kill window. The `CapitalReallocator` computes risk-adjusted returns and suggests reallocation. All reallocation decisions are advisory in Phase 1 — no automated fund transfers.

```
portfolio/src/main/kotlin/com/autoshipper/portfolio/
├── domain/
│   ├── Experiment.kt              (aggregate: budget, window, status)
│   └── ExperimentStatus.kt        (enum)
├── domain/service/
│   ├── ExperimentService.kt       (CRUD + validation)
│   ├── KillWindowMonitor.kt       (@Scheduled daily)
│   ├── CapitalReallocator.kt      (risk-adjusted return scoring)
│   └── PortfolioReporter.kt       (KPI aggregation)
├── handler/
│   └── PortfolioController.kt
└── config/
    └── PortfolioConfig.kt         (kill window, cache TTL)
```

## Architecture Decisions

- **`Experiment` is separate from `Sku`**: An experiment is a hypothesis + budget + window. It gates the creation of a SKU — a SKU is only created after an experiment passes validation. This keeps the catalog clean and free of experiment metadata.
- **`KillWindowMonitor` reads from `capital` module's snapshots**: No direct dependency on capital module code — reads from a shared DB view (`sku_portfolio_view`) that aggregates margin signal data. Avoids circular module dependency.
- **Capital reallocation is advisory in Phase 1**: `CapitalReallocator` produces a `ReallocationRecommendation` that is surfaced in the dashboard for human approval. No automatic fund movement.
- **Portfolio KPIs cached with Caffeine (5-minute TTL)**: These are read-heavy, write-rarely aggregates. Caching prevents repeated expensive aggregation queries.

## Layer-by-Layer Implementation

### Domain Layer
- `Experiment`: id, name, hypothesisDescription, budget, validationWindowDays, status, createdAt, launchedSkuId
- `ExperimentStatus`: ACTIVE, VALIDATED, FAILED, LAUNCHED, TERMINATED

### Domain Service
- `ExperimentService.create(request)`: creates experiment in ACTIVE status
- `ExperimentService.markValidated(experimentId, skuId)`: transitions to VALIDATED, links to SKU
- `KillWindowMonitor.scan()`: finds SKUs with sustained negative signals past kill window, emits termination recommendation
- `CapitalReallocator.recommend()`: scores all active SKUs by risk-adjusted return, returns ranked `ReallocationRecommendation`
- `PortfolioReporter.summary()`: counts, blended margin, capital deployed — cached

### Handler Layer
- `GET /api/portfolio/summary`, `GET /api/portfolio/experiments`, `POST /api/portfolio/experiments`

## Task Breakdown

### Domain Layer
- [ ] Implement `ExperimentStatus` enum with 5 statuses
- [ ] Implement `Experiment` JPA entity (id, name, hypothesis, budgetAmount, budgetCurrency, validationWindowDays, status, launchedSkuId)
- [ ] Implement `ReallocationRecommendation` data class (rankedSkus with risk-adjusted return scores, freedCapital, recommendedTarget)
- [ ] Define `PortfolioConfig` `@ConfigurationProperties` (killWindowDays=30, kpiCacheTtlMinutes=5)

### Domain Service
- [ ] Implement `ExperimentService.create(name, hypothesis, budget, windowDays)`
- [ ] Implement `ExperimentService.markValidated(experimentId, skuId)` — links experiment to launched SKU
- [ ] Implement `ExperimentService.markFailed(experimentId)` — transitions to FAILED
- [ ] Implement `KillWindowMonitor` `@Scheduled(cron = "0 0 1 * * *")` (1am daily)
- [ ] Implement kill window scan: find SKUs with negative margin signals for > killWindowDays
- [ ] Emit termination recommendation event for qualifying SKUs (human-approved in Phase 1)
- [ ] Implement `CapitalReallocator.recommend()` — score active SKUs by (avg net margin × revenue volume / risk factor)
- [ ] Implement `PortfolioReporter.summary()` with Caffeine cache (5-minute TTL)
- [ ] Summary includes: totalExperiments, activeSkus, terminatedSkus, blendedMargin, capitalDeployed

### Handler Layer
- [ ] Implement `GET /api/portfolio/summary` returning `PortfolioSummaryResponse`
- [ ] Implement `GET /api/portfolio/experiments` returning paginated experiment list
- [ ] Implement `POST /api/portfolio/experiments` creating new experiment
- [ ] Implement `GET /api/portfolio/reallocation` returning `ReallocationRecommendation`
- [ ] Add request/response DTOs for all endpoints

### Config Layer
- [ ] Configure Caffeine cache bean for portfolio KPIs in `PortfolioConfig`
- [ ] Add `spring.cache.type=caffeine` and TTL settings to `application.yml`

### Persistence (Common Layer)
- [ ] Write `V9__portfolio.sql` migration (experiments table)
- [ ] Create `sku_portfolio_view` SQL view aggregating SKU + capital data for kill window monitor
- [ ] Implement `ExperimentRepository`

## Testing Strategy

- Unit test `KillWindowMonitor`: SKU with 31-day negative signal → recommendation emitted; 29-day → no action
- Unit test `CapitalReallocator`: known SKU metrics → correct risk-adjusted ranking
- Unit test `PortfolioReporter`: correct aggregate counts and blended margin
- Integration test: experiment created → SKU launched from experiment → experiment status LAUNCHED
- Cache test: second call to `summary()` returns cached result without DB query

## Rollout Plan

1. Write `V9__portfolio.sql` and `sku_portfolio_view`
2. Implement `Experiment` entity and `ExperimentService`
3. Implement `KillWindowMonitor`
4. Implement `CapitalReallocator`
5. Implement `PortfolioReporter` with cache
6. Add REST handler
