# FR-010: Portfolio Orchestration

## Problem Statement

The system must operate as an active portfolio — not a static catalog. Without structured experiment tracking and capital reallocation logic, winners won't be scaled and losers won't be killed on time. The portfolio module must orchestrate the continuous cycle of experiment → validate → launch → scale/kill → reallocate.

## Business Requirements

- The system must support running multiple experiments monthly, each with a defined budget and validation window
- Experiments must transition to launched SKUs only when stress-test results pass (FR-005 gate)
- Underperforming SKUs must be terminated within 30 days of the first negative signal (configurable kill window)
- Capital freed from terminated SKUs must be automatically reallocated toward the highest risk-adjusted return opportunity in the active portfolio
- High-performing SKUs must be flagged for scaling (budget increase, channel expansion)
- SKUs that convert consistently may be flagged for conversion into branded verticals or subscription layers
- Portfolio-level KPIs must be visible: total experiments, active SKUs, terminated SKUs, capital deployed, blended margin

## Success Criteria

- `Experiment` aggregate with budget, validation window, status (`Active`, `Validated`, `Failed`, `Launched`, `Terminated`)
- `KillWindowMonitor` scheduled job identifies SKUs with sustained negative signals past 30 days
- `CapitalReallocator` identifies top risk-adjusted return opportunity and reallocates freed capital
- `ScalingFlagService` marks SKUs meeting scaling criteria for human review
- `PortfolioReport` exposes blended portfolio KPIs
- `GET /api/portfolio/summary` returns portfolio-level KPIs
- `GET /api/portfolio/experiments` returns all experiments with status
- `POST /api/portfolio/experiments` creates a new experiment
- Integration test: experiment → pass stress test → launch → negative signal → kill → capital reallocated

## Non-Functional Requirements

- Kill window monitor runs daily via `@Scheduled`
- Capital reallocation logged to `capital_reallocation_log` with before/after state (skuId, freedCapital, recommendedTargetSkuId, recommendedAt)
- Portfolio KPIs cached with 5-minute TTL (Caffeine or Redis)
- Reallocation decisions are advisory in Phase 1 — human-confirmed before executing fund transfers
- `DemandScanJob` (Google Trends RSS + Reddit + Amazon PA-API) is owned by this module per the solo-operator spec but is explicitly deferred to a follow-on FR — this FR covers experiment lifecycle and capital reallocation only

## Dependencies

- FR-001 (shared-domain-primitives) — `ExperimentId`, `SkuId`, domain events
- FR-003 (catalog-sku-lifecycle) — SKU state transitions
- FR-005 (catalog-stress-test) — `LaunchReadySku` is the gate from experiment to launch
- FR-009 (capital-protection) — monitors capital reserves and margin signals
