# FR-016: Demand Scan Job

**Linear Issue:** [RAT-12](https://linear.app/ratrace/issue/RAT-12/automated-deal-sourcing-demandscanjob-google-trends-reddit-amazon-pa)
**Consolidated from:** RAT-5 (FR-016: Discovery), RAT-8, RAT-10 (all cancelled per PM-007 AD-11)

## Problem Statement

The Commerce Engine cannot discover new product opportunities autonomously. SKUs must be created manually via `POST /api/skus`, which means the pipeline runs dry when the operator steps away. This was identified as **Gap #1** in the executive readiness assessment (PM-007).

The system's defensive layer (margin protection, vendor governance, fulfillment, capital reserves) is complete and autonomous, but the **offensive layer** — finding things to sell — does not exist. Without automated deal sourcing, the promise of a fully autonomous commerce engine is unfulfilled.

## Business Requirements

### BR-1: Automated Demand Signal Ingestion
A scheduled job (`DemandScanJob`) must run every 24 hours in the `portfolio` module, ingesting demand signals from public data sources and producing scored product candidates.

### BR-2: Phase 1 Data Sources
Three data sources for Phase 1:
- **CJ Dropshipping API** — supplier catalog with real pricing data; validates sourcing feasibility at $0 cost. This is the *sourcing* source — products are sourced from CJ, not from marketplace resellers.
- **Google Trends RSS** — trending search categories as a demand signal proxy ($0)
- **Amazon PA-API 5.0** — product discovery, pricing, BSR data as competition/demand proxy ($0). Used as a *demand signal only* — never as a sourcing channel (per AD-9: source-level pricing rule).

### BR-3: Candidate Scoring Engine
Each data source produces raw candidates. A scoring service evaluates them on three dimensions:
- **Demand score** (0-1): Google Trends interest level, BSR rank normalized
- **Margin potential score** (0-1): estimated unit cost (from CJ) vs category average selling price
- **Competition score** (0-1): number of sellers, review density, listing age
- **Composite score**: weighted combination of the above, with weights configurable in `application.yml`

Scoring thresholds for pass/fail also configurable in `application.yml`.

### BR-4: Deduplication via Trigram Similarity
- Enable the `pg_trgm` PostgreSQL extension
- Use `similarity()` function on product name + category to detect near-duplicates
- Trigram similarity > 0.7 against existing candidates = duplicate, skip
- No external API dependency, no cost, pure SQL
- **Phase 2 upgrade path:** semantic similarity search ("find candidates similar to SKUs that passed stress test"). Embedding options evaluated for Phase 2:

| Approach | Cost | Quality | Trade-off |
|---|---|---|---|
| OpenAI `text-embedding-3-small` | ~$0.02/1M tokens | High | External API dependency |
| Local `all-MiniLM-L6-v2` via ONNX | $0 | Good | ~80MB model bundle, no network calls |
| PostgreSQL `pg_trgm` (current Phase 1) | $0 | Moderate | Already in use; limited to lexical similarity |
| TF-IDF / trigram hashing | $0 | Moderate | Pure computation, no extensions needed |

Recommended Phase 2 path: local ONNX model (`all-MiniLM-L6-v2`) + pgvector — zero cost, no external dependency, true semantic similarity

### BR-5: Experiment Record Creation
Candidates that pass scoring thresholds automatically create `Experiment` records (via FR-010's `ExperimentService`) in ACTIVE status with:
- Auto-generated hypothesis from source data
- Source signal metadata for traceability (which source, raw scores, timestamp)
- Estimated margin per unit (projected from CJ cost vs category selling price)
- No budget field — zero-capital model (AD-8)
- Default 30-day validation window

### BR-6: Structured Rejection Logging
Candidates that fail scoring receive structured rejection logging:
- Rejection reason (which threshold failed)
- Individual dimension scores
- Source metadata
- This data feeds back into scoring weight tuning over time

### BR-7: Source-Level Pricing Rule (AD-9)
Discovery must always source from original suppliers (CJ Dropshipping, Printful, Gelato), never from marketplace resellers. Amazon/Google Trends data is used as *demand signals only*. The estimated cost in scoring must reflect original supplier cost, not marketplace reseller price.

### BR-8: Discovery Blacklist Integration
The `discovery_blacklist` table (already exists in V14 migration) must be consulted during scoring. Categories or suppliers that consistently generate refunds (identified by `RefundPatternAnalyzer`) are excluded from candidate scoring.

## Success Criteria

- [ ] `DemandScanJob` runs on a 24-hour schedule and ingests from at least 2 data sources (CJ + Google Trends minimum)
- [ ] Each candidate is scored with composite scoring across demand, margin potential, and competition dimensions
- [ ] `pg_trgm` extension is enabled; trigram similarity used for near-duplicate detection (threshold > 0.7)
- [ ] Candidates that pass scoring automatically create `Experiment` records via `ExperimentService`
- [ ] Structured rejection logging captures why candidates failed, with scores and metadata
- [ ] `DemandSignalProvider` interface exists with stub implementations for local dev (`@Profile("local")`)
- [ ] Discovery blacklist is checked during scoring
- [ ] Unit tests cover scoring logic (edge cases, threshold boundaries, weight configurations)
- [ ] Integration test: job runs -> candidate scored -> experiment created -> visible via `GET /api/portfolio/experiments`
- [ ] New REST endpoints expose scan results (candidates, rejections, last scan status) for the frontend
- [ ] DemandSignals frontend page wired up with real data from the new endpoints

## Non-Functional Requirements

### NFR-1: Testability
Each data source is behind a `DemandSignalProvider` interface. Stub implementations annotated with `@Profile("local")` provide deterministic test data for local development. Real implementations use `@Profile("!local")`.

### NFR-2: Incremental Rollout
Data sources can be enabled/disabled independently via configuration. The job should gracefully handle unavailable sources (log warning, continue with remaining sources).

### NFR-3: Idempotency
Running the job multiple times in the same day should not create duplicate candidates or experiments. pgvector dedup handles product-level dedup; a scan-run dedup mechanism prevents re-processing the same source data within a configurable window.

### NFR-4: Observability
- Log scan start/end with candidate counts
- Log each source's contribution (candidates found, passed, rejected)
- Micrometer metrics: `demand_scan_candidates_total`, `demand_scan_experiments_created`, `demand_scan_rejections_total`

### NFR-5: Resilience
Individual source failures should not abort the entire scan. Circuit breaker patterns (already established in `application.yml` for carrier/platform APIs) should be applied to external API calls.

### NFR-6: Architectural Placement
Per solo-operator spec section 2.4 and PM-007, `DemandScanJob` belongs in the `portfolio` module — NOT a separate discovery module. It writes `Experiment` records via `ExperimentService`, not standalone entities.

## Dependencies

- **FR-010** (Portfolio Orchestration) — IMPLEMENTED. Provides `Experiment` entity, `ExperimentService`, `ExperimentRepository`
- **V14 migration** — IMPLEMENTED. Provides `discovery_blacklist` table
- **V16 migration** — IMPLEMENTED. Added `source_signal`, `estimated_margin_per_unit` columns to experiments
- **pg_trgm PostgreSQL extension** — NEW. Must be enabled via Flyway migration (no external dependencies)
