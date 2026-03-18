# FR-016: Demand Scan Job — Implementation Plan

## Technical Design

### Architecture Overview

`DemandScanJob` is a `@Scheduled` job in the `portfolio` module that orchestrates a daily demand signal scan pipeline:

```
DemandScanJob (scheduler)
  │
  ├─ Collect: DemandSignalProvider[] → RawCandidate[]
  │    ├─ CjDropshippingProvider   (sourcing + pricing)
  │    ├─ GoogleTrendsProvider      (demand signal)
  │    └─ AmazonCreatorsApiProvider  (demand + competition signal)
  │
  ├─ Dedup: CandidateDeduplicationService (pg_trgm similarity > 0.7)
  │
  ├─ Filter: DiscoveryBlacklistRepository (exclude blacklisted categories/suppliers)
  │
  ├─ Score: CandidateScoringService → ScoredCandidate[]
  │    ├─ demandScore (0-1)
  │    ├─ marginPotentialScore (0-1)
  │    ├─ competitionScore (0-1)
  │    └─ compositeScore (weighted)
  │
  ├─ Accept: → ExperimentService.create() for passing candidates
  │
  └─ Reject: → CandidateRejectionRepository for structured logging
```

All new code lives in `modules/portfolio/`. No new modules.

### Key Domain Types

```kotlin
// Raw output from a DemandSignalProvider
data class RawCandidate(
    val productName: String,
    val category: String,
    val description: String,
    val sourceType: String,        // "CJ_DROPSHIPPING", "GOOGLE_TRENDS", "AMAZON_CREATORS_API"
    val supplierUnitCost: Money?,  // only from CJ (sourcing source)
    val estimatedSellingPrice: Money?,
    val demandSignals: Map<String, String>,  // source-specific metadata
)

// After scoring
data class ScoredCandidate(
    val raw: RawCandidate,
    val demandScore: BigDecimal,
    val marginPotentialScore: BigDecimal,
    val competitionScore: BigDecimal,
    val compositeScore: BigDecimal,
    val passed: Boolean,
)
```

### Database Changes (V18)

- Enable `pg_trgm` extension
- `demand_candidates` table — persists scored candidates for frontend display and dedup
- `candidate_rejections` table — structured rejection log
- `demand_scan_runs` table — tracks scan executions for idempotency and status display
- GIN trigram index on `demand_candidates.product_name` for similarity queries

## External API Reference

### 1. CJ Dropshipping API V2

- **Docs:** [https://developers.cjdropshipping.com/](https://developers.cjdropshipping.com/) | [Product endpoints](https://developers.cjdropshipping.com/en/api/api2/api/product.html)
- **Product Search:** `GET https://developers.cjdropshipping.com/api2.0/v1/product/listV2` (Elasticsearch-backed)
- **Auth:** `CJ-Access-Token` header (obtain via OAuth at `/api2.0/v1/authentication/getAccessToken`)
- **Rate limit:** 1,000 requests/day (free tier)
- **Key params:** `keyWord`, `categoryId`, `countryCode`, `startSellPrice`/`endSellPrice`, `productType`, `page` (1-1000), `size` (1-100)
- **Response:** paginated product list with `pid`, `productNameEn`, `sellPrice`, `categoryName`, `productImage`, `productWeight`, `productSku`
- **Category list:** `GET /api2.0/v1/product/getCategory` — 3-tier hierarchy with IDs
- **Product detail:** `GET /api2.0/v1/product/query?pid={pid}` — full spec, variants, dimensions, pricing
- **Inventory:** `GET /api2.0/v1/product/stock/queryByVid?vid={vid}` — per-warehouse stock levels

### 2. Amazon Creators API (replaces PA-API 5.0)

- **Docs:** [https://affiliate-program.amazon.com/creatorsapi/docs/en-us/introduction](https://affiliate-program.amazon.com/creatorsapi/docs/en-us/introduction)
- **Migration note:** PA-API 5.0 **deprecated April 30, 2026**. Creators API is the replacement. Same operations (`SearchItems`, `GetItems`, `GetVariations`) with lowerCamelCase params.
- **Auth:** OAuth 2.0 client-credentials flow (replaces AWS Signature V4). Token lifetime ~1 hour (cache it). Credentials: `Credential ID` + `Credential Secret` from Associates Central (not AWS keys).
- **Regional credentials:** single credential set per region (NA: US/CA/MX/BR, EU: UK/DE/FR/etc, FE: JP/SG/AU)
- **SearchItems:** `POST` — requires `partnerTag`, `partnerType: "Associates"`, and at least one of `keywords`/`brand`/`title`
- **Key params:** `searchIndex` (category), `itemCount` (1-10), `itemPage` (1-10), `minPrice`/`maxPrice`, `sortBy`, `resources` (requested response fields)
- **Response:** `searchResult.items[]` with `asin`, `detailPageURL`, `itemInfo` (title, features, classifications), `offersV2` (price, availability, seller count), `images`, `browseNodeInfo`
- **Key fields for scoring:** BSR (via `browseNodeInfo`), seller count (via `offersV2.listingCount`), price, review count

### 3. Google Trends RSS Feed

- **URL:** `https://trends.google.com/trending/rss?geo={country_code}` (e.g., `?geo=US`)
- **Format:** RSS 2.0 with Google Trends namespace (`xmlns:ht`)
- **Auth:** None (public feed)
- **Rate limit:** None documented (standard HTTP — respect `Cache-Control` headers)
- **Note:** The official Google Trends API is alpha-only (not publicly available). We use the public RSS feed directly.

**Item fields:**

| XML Element | Description | Example |
|---|---|---|
| `<title>` | Trending search query | `"bamboo kitchen set"` |
| `<ht:approx_traffic>` | Search volume estimate | `"10000+"`, `"1000+"`, `"200+"` |
| `<pubDate>` | Trend timestamp | `"Tue, 17 Mar 2026 19:30:00 -0700"` |
| `<ht:picture>` | Associated image URL | Google-proxied image link |
| `<ht:picture_source>` | Image attribution | `"Yahoo News"` |
| `<ht:news_item>` | Related news articles (repeating) | Contains `news_item_title`, `news_item_url`, `news_item_source` |

**Limitations:** No category tags, no related queries, no historical interest data. Provides trending *queries* with approximate traffic volume — useful as a demand signal but requires cross-referencing with CJ catalog for product matching.

## Architecture Decisions

### AD-1: pg_trgm for Phase 1 Dedup (not pgvector)
pg_trgm `similarity()` provides lexical near-duplicate detection at zero cost with no external API dependencies. Phase 2 upgrades to local ONNX embeddings + pgvector for semantic similarity.

### AD-2: DemandSignalProvider Interface + Profile-Based Stubs
Each data source is behind a `DemandSignalProvider` interface. Real implementations use `@Profile("!local")`, stubs use `@Profile("local")`. This enables local dev without API keys and deterministic testing.

### AD-3: Scan Run Idempotency
`demand_scan_runs` tracks each execution. Candidates store `scan_run_id` as a foreign key. The job checks the last successful run timestamp and skips re-processing within a configurable cooldown window (default 20h to allow for schedule drift).

### AD-5: Amazon Creators API (not PA-API 5.0)
Amazon PA-API 5.0 is deprecated April 30, 2026. We implement against the Creators API from the start. Key differences: OAuth 2.0 client-credentials auth (not AWS Signature V4), lowerCamelCase params (`partnerTag` not `PartnerTag`), regional credentials (one set per NA/EU/FE region). Same `SearchItems` operation shape — returns ASIN, title, price, BSR, seller count.

### AD-6: Google Trends RSS (not Trends API)
The official Google Trends API is alpha-only with no public access. We use the public RSS feed at `trends.google.com/trending/rss?geo=US` which provides trending search queries with approximate traffic volume. Limitation: no category data or historical interest — the feed gives us *what's trending now* as a demand signal, which we cross-reference against the CJ catalog.

### AD-4: Graceful Source Degradation
Individual source failures (API timeouts, rate limits) are caught and logged. The scan continues with remaining sources. A scan with at least 1 successful source is considered valid.

## Layer-by-Layer Implementation

### Database Layer (Flyway V18)
- Enable `pg_trgm` extension
- Create `demand_scan_runs` table (id, started_at, completed_at, status, sources_queried, candidates_found, experiments_created, rejections)
- Create `demand_candidates` table (id, scan_run_id FK, product_name, category, description, source_type, supplier_unit_cost, supplier_cost_currency, estimated_selling_price, selling_price_currency, demand_score, margin_potential_score, competition_score, composite_score, passed, demand_signals JSONB, created_at)
- Create `candidate_rejections` table (id, scan_run_id FK, product_name, category, source_type, rejection_reason, demand_score, margin_potential_score, competition_score, composite_score, metadata JSONB, created_at)
- GIN trigram index: `CREATE INDEX idx_demand_candidates_name_trgm ON demand_candidates USING gin (product_name gin_trgm_ops)`
- Index on `demand_candidates.scan_run_id`
- Index on `candidate_rejections.scan_run_id`

### Domain Layer

#### Interfaces
- `DemandSignalProvider` — `fun sourceType(): String`, `fun fetch(): List<RawCandidate>`
- `RawCandidate` data class (see above)
- `ScoredCandidate` data class (see above)

#### Entities (JPA)
- `DemandCandidate` — maps to `demand_candidates` table
- `CandidateRejection` — maps to `candidate_rejections` table
- `DemandScanRun` — maps to `demand_scan_runs` table
- `DiscoveryBlacklistEntry` — maps to existing `discovery_blacklist` table

#### Services
- `CandidateScoringService` — takes `List<RawCandidate>`, scores each on 3 dimensions, returns `List<ScoredCandidate>`. Weights and thresholds from `DemandScanConfig`.
- `CandidateDeduplicationService` — checks `similarity(product_name, ?) > 0.7` via native query on `demand_candidates` table. Also checks against existing experiment names.
- `DemandScanJob` — `@Scheduled(cron = "0 0 3 * * *")` (03:00 daily). Orchestrates the full pipeline: collect → dedup → blacklist filter → score → accept/reject.

### Proxy Layer (Data Source Adapters)

#### Real Implementations (`@Profile("!local")`)
- `CjDropshippingAdapter` — REST client for CJ product search API. Returns sourcing candidates with `supplierUnitCost`.
- `GoogleTrendsAdapter` — RSS feed parser for trending categories. Returns demand signal candidates.
- `AmazonCreatorsApiAdapter` — Amazon Creators API client (OAuth 2.0, replaces deprecated PA-API 5.0). Returns competition/demand data (BSR, seller count, pricing).

#### Stub Implementations (`@Profile("local")`)
- `StubCjDropshippingProvider` — returns hardcoded product candidates with realistic pricing
- `StubGoogleTrendsProvider` — returns hardcoded trending categories
- `StubAmazonCreatorsApiProvider` — returns hardcoded BSR/competition data

### Config Layer
- `DemandScanConfig` — `@ConfigurationProperties(prefix = "demand-scan")` with:
  - `enabled: Boolean` (default true)
  - `cooldownHours: Int` (default 20)
  - `validationWindowDays: Int` (default 30)
  - `scoringWeights.demand: Double` (default 0.4)
  - `scoringWeights.marginPotential: Double` (default 0.35)
  - `scoringWeights.competition: Double` (default 0.25)
  - `scoringThreshold: Double` (default 0.6)
  - `dedupSimilarityThreshold: Double` (default 0.7)
- `application.yml` additions under `demand-scan:` prefix

### Persistence Layer
- `DemandCandidateRepository` extends `JpaRepository<DemandCandidate, UUID>`
  - `findByScanRunId(scanRunId: UUID): List<DemandCandidate>`
  - Native query: `findSimilarByName(name: String, threshold: Double): List<DemandCandidate>` using `similarity()`
- `CandidateRejectionRepository` extends `JpaRepository<CandidateRejection, UUID>`
  - `findByScanRunId(scanRunId: UUID): List<CandidateRejection>`
- `DemandScanRunRepository` extends `JpaRepository<DemandScanRun, UUID>`
  - `findTopByOrderByStartedAtDesc(): DemandScanRun?`
  - `findByStatus(status: String): List<DemandScanRun>`
- `DiscoveryBlacklistRepository` extends `JpaRepository<DiscoveryBlacklistEntry, UUID>`
  - `findAll(): List<DiscoveryBlacklistEntry>`
  - `existsByKeyword(keyword: String): Boolean`

### Handler Layer (REST Endpoints)

New endpoints in `PortfolioController` or a dedicated `DemandScanController`:
- `GET /api/portfolio/demand-scan/status` — last scan run summary (started, completed, counts)
- `GET /api/portfolio/demand-scan/candidates` — paginated list of scored candidates from latest run
- `GET /api/portfolio/demand-scan/rejections` — paginated list of rejections from latest run
- `POST /api/portfolio/demand-scan/trigger` — manually trigger a scan (for development/testing)

### Frontend Layer

Wire up `DemandSignalsPage.tsx` with real data:
- API hooks in `frontend/src/api/portfolio.ts`: `useDemandScanStatus()`, `useDemandCandidates()`, `useDemandRejections()`, `useTriggerDemandScan()`
- TypeScript types in `frontend/src/api/types.ts`
- Replace placeholder content with:
  - Scan status header (last run time, next scheduled, candidate/experiment counts)
  - Scored candidates table (name, category, source, scores, composite, passed/failed)
  - Rejections table (expandable, shows why each candidate was rejected)
  - Manual trigger button

## Task Breakdown

### Database Layer
- [x] Create Flyway migration V18 — pg_trgm extension, demand_scan_runs, demand_candidates, candidate_rejections tables with indexes

### Domain Layer — Types & Interfaces
- [x] Create `DemandSignalProvider` interface
- [x] Create `RawCandidate` data class
- [x] Create `ScoredCandidate` data class

### Domain Layer — Entities
- [x] Create `DemandCandidate` JPA entity
- [x] Create `CandidateRejection` JPA entity
- [x] Create `DemandScanRun` JPA entity
- [x] Create `DiscoveryBlacklistEntry` JPA entity

### Persistence Layer
- [x] Create `DemandCandidateRepository` with native trigram similarity query
- [x] Create `CandidateRejectionRepository`
- [x] Create `DemandScanRunRepository`
- [x] Create `DiscoveryBlacklistRepository`

### Domain Layer — Services
- [x] Implement `CandidateScoringService` (scoring logic, weights, thresholds)
- [x] Implement `CandidateDeduplicationService` (pg_trgm similarity check)
- [x] Implement `DemandScanJob` (scheduled orchestrator, full pipeline)

### Config Layer
- [x] Create `DemandScanConfig` configuration properties class
- [x] Add `demand-scan` section to `application.yml`

### Proxy Layer — Stub Providers
- [x] Implement `StubCjDropshippingProvider` (`@Profile("local")`)
- [x] Implement `StubGoogleTrendsProvider` (`@Profile("local")`)
- [x] Implement `StubAmazonCreatorsApiProvider` (`@Profile("local")`)

### Proxy Layer — Real Providers
- [x] Implement `CjDropshippingAdapter` (`@Profile("!local")`) — see API Reference §1
- [x] Implement `GoogleTrendsAdapter` (`@Profile("!local")`) — see API Reference §3
- [x] Implement `AmazonCreatorsApiAdapter` (`@Profile("!local")`) — see API Reference §2

### Handler Layer
- [x] Add demand scan REST endpoints (status, candidates, rejections, trigger)
- [x] Add request/response DTOs for demand scan endpoints

### Frontend Layer
- [x] Add TypeScript types for demand scan responses in `types.ts`
- [x] Add API hooks in `portfolio.ts` (useDemandScanStatus, useDemandCandidates, useDemandRejections, useTriggerDemandScan)
- [x] Rewrite `DemandSignalsPage.tsx` with scan status, candidates table, rejections view, and manual trigger

### Testing
- [x] Unit tests for `CandidateScoringService` (edge cases, threshold boundaries, weight configurations)
- [x] Unit tests for `CandidateDeduplicationService`
- [x] Unit tests for `DemandScanJob` (orchestration logic, source failure handling, idempotency)
- [x] Unit tests for stub providers (verify realistic output structure)

## Testing Strategy

### Unit Tests
- **CandidateScoringServiceTest**: scoring with default weights, custom weights, threshold boundaries (just below/at/above), zero scores, missing data handling
- **CandidateDeduplicationServiceTest**: exact duplicates, near-duplicates (similarity > 0.7), distinct products (similarity < 0.7), empty database
- **DemandScanJobTest**: happy path (all sources succeed), partial failure (1 source fails, others continue), all sources fail, idempotency (run twice within cooldown), blacklist filtering, experiment creation via ExperimentService

### Integration Tests
- Full pipeline: job runs → sources queried → candidates scored → experiments created → visible via `GET /api/portfolio/experiments`

## Rollout Plan

1. Deploy V18 migration (pg_trgm extension, new tables)
2. Deploy code with stubs active (local profile)
3. Verify job runs with stub data, experiments created correctly
4. Switch to real providers (remove local profile), configure API keys
5. Monitor first few runs via `/api/portfolio/demand-scan/status` endpoint
