# FR-010: Portfolio Orchestration

## Problem Statement

The system must operate as an active portfolio — not a static catalog. Without structured experiment tracking and capital reallocation logic, winners won't be scaled and losers won't be killed on time. The portfolio module must orchestrate the continuous cycle of experiment → validate → launch → scale/kill → reallocate.

## Business Requirements

- The system must support running multiple experiments concurrently, each with a defined validation window
- An experiment is a **listing hypothesis** — "list this product and see if it sells." There is no upfront capital allocation; the customer's payment covers all costs (sourcing, shipping, fees) and the remainder is profit
- Experiments must transition to launched SKUs only when stress-test results pass (FR-005 gate)
- Underperforming SKUs must be terminated within 30 days of the first negative signal (configurable kill window)
- When a SKU is terminated, the system must automatically prioritize the next-best listing opportunity — reallocation is about **attention and listing slots**, not fund transfers
- High-performing SKUs must be flagged for scaling (increased visibility, channel expansion, SEO priority)
- SKUs that convert consistently may be flagged for conversion into branded verticals or subscription layers
- Portfolio-level KPIs must be visible: total experiments, active SKUs, terminated SKUs, blended margin, total profit

## Success Criteria

- `Experiment` aggregate with hypothesis, validation window, status (`Active`, `Validated`, `Failed`, `Launched`, `Terminated`)
- `KillWindowMonitor` scheduled job identifies SKUs with sustained negative signals past 30 days
- `PriorityRanker` ranks active and candidate SKUs by risk-adjusted return to determine listing priority
- `ScalingFlagService` marks SKUs meeting scaling criteria for automated scaling actions
- `PortfolioReport` exposes blended portfolio KPIs
- `GET /api/portfolio/summary` returns portfolio-level KPIs
- `GET /api/portfolio/experiments` returns all experiments with status
- `POST /api/portfolio/experiments` creates a new experiment
- Integration test: experiment → pass stress test → launch → negative signal → kill → next-best candidate prioritized

## Non-Functional Requirements

- Kill window monitor runs daily via `@Scheduled`
- Priority ranking logged to `priority_ranking_log` with state (skuId, score, rank, rankedAt)
- Portfolio KPIs cached with 5-minute TTL (Caffeine or Redis)
- Priority ranking is advisory in Phase 1 — system ranks candidates, human confirms which to list next
- `DemandScanJob` (Google Trends RSS + Reddit + Amazon PA-API) is owned by this module per the solo-operator spec but is explicitly deferred to a follow-on FR (RAT-12) — this FR covers experiment lifecycle and priority ranking only

## Dependencies

- FR-001 (shared-domain-primitives) — `ExperimentId`, `SkuId`, domain events
- FR-003 (catalog-sku-lifecycle) — SKU state transitions
- FR-005 (catalog-stress-test) — `LaunchReadySku` is the gate from experiment to launch
- FR-009 (capital-protection) — monitors capital reserves and margin signals