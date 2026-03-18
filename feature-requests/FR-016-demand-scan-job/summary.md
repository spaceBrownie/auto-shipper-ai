# FR-016: Demand Scan Job — Implementation Summary

## Feature Summary

Implemented `DemandScanJob`, a daily scheduled pipeline in the `portfolio` module that autonomously discovers product candidates from three external data sources (CJ Dropshipping, Google Trends RSS, Amazon Creators API), deduplicates them via pg_trgm trigram similarity, scores them on demand/margin/competition dimensions, and creates `Experiment` records for passing candidates. This closes **Gap #1** (missing offensive layer) identified in the executive readiness assessment.

## Changes Made

### Database Layer
- **V18 migration** (`V18__demand_scan.sql`): Enables `pg_trgm` extension; creates `demand_scan_runs`, `demand_candidates`, and `candidate_rejections` tables with GIN trigram index and FK indexes.

### Domain Layer
- **`DemandSignalProvider`** interface: Pluggable data source contract (`sourceType()`, `fetch()`)
- **`RawCandidate`** / **`ScoredCandidate`** data classes: Pipeline data types carrying product info, scores, and pass/fail status
- **JPA Entities**: `DemandCandidate`, `CandidateRejection`, `DemandScanRun`, `DiscoveryBlacklistEntry`

### Persistence Layer
- 4 Spring Data JPA repositories with custom queries:
  - `DemandCandidateRepository` — includes native `similarity()` query for trigram dedup
  - `CandidateRejectionRepository`, `DemandScanRunRepository`, `DiscoveryBlacklistRepository`

### Service Layer
- **`CandidateScoringService`** — Scores candidates on 3 dimensions (demand, margin potential, competition) with configurable weights and threshold. Uses tiered scoring based on traffic volume, BSR rank, margin %, and seller count.
- **`CandidateDeduplicationService`** — Checks for near-duplicates against existing candidates (via pg_trgm) and experiment names (via in-memory trigram similarity).
- **`DemandScanJob`** — `@Scheduled(cron = "0 0 3 * * *")` orchestrator. Pipeline: collect from sources → dedup → blacklist filter → score → accept/reject. Supports cooldown window, graceful source degradation, and idempotent scan runs.

### Proxy Layer
- **Stub providers** (`@Profile("local")`): `StubCjDropshippingProvider`, `StubGoogleTrendsProvider`, `StubAmazonCreatorsApiProvider` — deterministic test data
- **Real providers** (`@Profile("!local")`): `CjDropshippingAdapter` (REST), `GoogleTrendsAdapter` (RSS/XML), `AmazonCreatorsApiAdapter` (OAuth 2.0 + REST)

### Config Layer
- **`DemandScanConfig`** (`@ConfigurationProperties(prefix = "demand-scan")`): enabled, cooldownHours, validationWindowDays, scoringWeights, scoringThreshold, dedupSimilarityThreshold
- **`application.yml`** additions: `demand-scan:` section + API key config for CJ/Amazon/Google Trends

### Handler Layer
- **`DemandScanController`** (`/api/portfolio/demand-scan/`):
  - `GET /status` — last scan run summary
  - `GET /candidates` — scored candidates from latest run
  - `GET /rejections` — rejections from latest run
  - `POST /trigger` — manually trigger a scan

### Frontend Layer
- **TypeScript types**: `DemandScanStatusResponse`, `DemandCandidateResponse`, `CandidateRejectionResponse`
- **API hooks**: `useDemandScanStatus()`, `useDemandCandidates()`, `useDemandRejections()`, `useTriggerDemandScan()`
- **`DemandSignalsPage.tsx`**: Full rewrite from placeholder — scan status header with KPI cards, candidates table with color-coded score bars, collapsible rejections section, trigger button

## Files Modified

### New Files (28)
| File | Description |
|---|---|
| `modules/app/src/main/resources/db/migration/V18__demand_scan.sql` | Flyway migration: pg_trgm, 3 tables, indexes |
| `modules/portfolio/src/main/kotlin/.../domain/DemandSignalProvider.kt` | Provider interface |
| `modules/portfolio/src/main/kotlin/.../domain/RawCandidate.kt` | Raw candidate data class |
| `modules/portfolio/src/main/kotlin/.../domain/ScoredCandidate.kt` | Scored candidate data class |
| `modules/portfolio/src/main/kotlin/.../domain/DemandCandidate.kt` | JPA entity |
| `modules/portfolio/src/main/kotlin/.../domain/CandidateRejection.kt` | JPA entity |
| `modules/portfolio/src/main/kotlin/.../domain/DemandScanRun.kt` | JPA entity |
| `modules/portfolio/src/main/kotlin/.../domain/DiscoveryBlacklistEntry.kt` | JPA entity |
| `modules/portfolio/src/main/kotlin/.../persistence/DemandCandidateRepository.kt` | Repository with trigram query |
| `modules/portfolio/src/main/kotlin/.../persistence/CandidateRejectionRepository.kt` | Repository |
| `modules/portfolio/src/main/kotlin/.../persistence/DemandScanRunRepository.kt` | Repository |
| `modules/portfolio/src/main/kotlin/.../persistence/DiscoveryBlacklistRepository.kt` | Repository |
| `modules/portfolio/src/main/kotlin/.../config/DemandScanConfig.kt` | Configuration properties |
| `modules/portfolio/src/main/kotlin/.../domain/service/CandidateScoringService.kt` | Scoring engine |
| `modules/portfolio/src/main/kotlin/.../domain/service/CandidateDeduplicationService.kt` | Dedup service |
| `modules/portfolio/src/main/kotlin/.../domain/service/DemandScanJob.kt` | Scheduled orchestrator |
| `modules/portfolio/src/main/kotlin/.../proxy/StubCjDropshippingProvider.kt` | Stub (local profile) |
| `modules/portfolio/src/main/kotlin/.../proxy/StubGoogleTrendsProvider.kt` | Stub (local profile) |
| `modules/portfolio/src/main/kotlin/.../proxy/StubAmazonCreatorsApiProvider.kt` | Stub (local profile) |
| `modules/portfolio/src/main/kotlin/.../proxy/CjDropshippingAdapter.kt` | Real CJ API adapter |
| `modules/portfolio/src/main/kotlin/.../proxy/GoogleTrendsAdapter.kt` | Real Google Trends RSS adapter |
| `modules/portfolio/src/main/kotlin/.../proxy/AmazonCreatorsApiAdapter.kt` | Real Amazon Creators API adapter |
| `modules/portfolio/src/main/kotlin/.../handler/DemandScanController.kt` | REST controller + DTOs |
| `modules/portfolio/src/test/.../domain/service/CandidateScoringServiceTest.kt` | 8 tests |
| `modules/portfolio/src/test/.../domain/service/CandidateDeduplicationServiceTest.kt` | 5 tests |
| `modules/portfolio/src/test/.../domain/service/DemandScanJobTest.kt` | 8 tests |
| `modules/portfolio/src/test/.../proxy/StubProviderTest.kt` | 9 tests |

### Modified Files (3)
| File | Description |
|---|---|
| `modules/app/src/main/resources/application.yml` | Added demand-scan config + API key sections |
| `frontend/src/api/types.ts` | Added 3 demand scan response interfaces |
| `frontend/src/api/portfolio.ts` | Added 4 hooks (3 queries + 1 mutation) |

### Rewritten Files (1)
| File | Description |
|---|---|
| `frontend/src/pages/DemandSignalsPage.tsx` | Full rewrite: placeholder → functional demand scan dashboard |

## Testing Completed

- **30 new unit tests**, all passing:
  - `CandidateScoringServiceTest` (8): scoring tiers, threshold boundaries, missing data defaults
  - `CandidateDeduplicationServiceTest` (5): duplicates, batch internal dedup, experiment name matching
  - `DemandScanJobTest` (8): happy path, disabled/cooldown skip, graceful degradation, blacklist filtering
  - `StubProviderTest` (9): output structure validation for all 3 stubs
- **Full build**: `./gradlew build` passes
- **Frontend type-check**: `npx tsc --noEmit` passes

## Deployment Notes

1. **Run V18 migration** before deploying code: `./gradlew flywayMigrate`
   - Requires PostgreSQL `pg_trgm` extension (included in standard PostgreSQL)
2. **Stub mode (default)**: With `local` Spring profile active, the job runs with hardcoded test data — no API keys needed
3. **Production mode**: Set environment variables for API keys:
   - `CJ_ACCESS_TOKEN` — CJ Dropshipping API token
   - `AMAZON_CREDENTIAL_ID`, `AMAZON_CREDENTIAL_SECRET`, `AMAZON_PARTNER_TAG` — Amazon Creators API OAuth credentials
   - Google Trends RSS requires no authentication
4. **Schedule**: Job runs daily at 03:00 UTC. Manual trigger available via `POST /api/portfolio/demand-scan/trigger`
5. **Circuit breakers**: Consider adding resilience4j entries for `cj-rate`, `amazon-creators` in Phase 2
