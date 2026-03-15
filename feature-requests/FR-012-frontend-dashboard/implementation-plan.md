> **Path update (FR-013):** The `frontend/` directory is at the repository root.
> Backend modules are under `modules/<name>/src/...`.

# FR-012: Frontend Dashboard — Implementation Plan

## Technical Design

A React + Vite + TypeScript application using shadcn/ui for components, TanStack Query for
data fetching, and Recharts for chart visualization. The app is a read-heavy internal
operations dashboard with 9 views matching the solo-operator spec Section 7.1 (plus a
compliance view). All data is fetched from the Spring Boot backend REST API (27 existing
endpoints + 2 new ones).

```
frontend/
├── src/
│   ├── main.tsx
│   ├── App.tsx                        (router + layout shell)
│   ├── api/
│   │   ├── client.ts                  (fetch base client, error handling)
│   │   ├── types.ts                   (TypeScript interfaces matching backend DTOs)
│   │   ├── skus.ts                    (SKU, cost gate, stress test hooks)
│   │   ├── pricing.ts                 (pricing history hooks)
│   │   ├── vendors.ts                 (vendor CRUD + score hooks)
│   │   ├── capital.ts                 (reserve, P&L, margin history hooks)
│   │   ├── portfolio.ts              (summary, experiments, kill recs, refund alerts, priority ranking)
│   │   ├── compliance.ts             (compliance status + audit hooks)
│   │   └── orders.ts                  (order + tracking hooks)
│   ├── pages/
│   │   ├── SkuPortfolioPage.tsx       (SKU list with state filter)
│   │   ├── SkuDetailPage.tsx          (cost breakdown, stress test, pricing, P&L, compliance, state history)
│   │   ├── CostGateRunnerPage.tsx     (input product → verify costs → stress test → approve/reject)
│   │   ├── VendorScorecardPage.tsx    (vendor list with reliability scores + breach history)
│   │   ├── MarginMonitorPage.tsx      (90-day margin charts per SKU + portfolio)
│   │   ├── ExperimentTrackerPage.tsx  (experiments with validation window countdown)
│   │   ├── CapitalOverviewPage.tsx    (reserve gauge, SKU P&L)
│   │   ├── DemandSignalsPage.tsx      (placeholder — DemandScanJob not yet implemented)
│   │   ├── KillLogPage.tsx            (terminated SKUs + confirmed kill recs with reasons)
│   │   └── ComplianceStatusPage.tsx   (per-SKU compliance audit trail)
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Sidebar.tsx            (navigation menu)
│   │   │   └── AppShell.tsx           (sidebar + main content area)
│   │   ├── StatusBadge.tsx            (color-coded state labels)
│   │   ├── MarginTrendChart.tsx       (Recharts LineChart — 90-day margin)
│   │   ├── ReserveGauge.tsx           (reserve balance vs target range)
│   │   ├── VendorScoreBar.tsx         (0–100 score progress bar)
│   │   ├── CostBreakdownTable.tsx     (13-component cost envelope table)
│   │   ├── StressTestResultCard.tsx   (pass/fail with stressed amounts)
│   │   ├── KpiCard.tsx                (summary metric card)
│   │   └── DataTable.tsx              (reusable sortable/filterable table)
│   └── lib/
│       ├── utils.ts                   (shadcn/ui cn() utility)
│       └── formatters.ts             (money, percentage, date formatters)
├── index.html
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.js
├── postcss.config.js
├── components.json                    (shadcn/ui config)
└── package.json
```

## Architecture Decisions

- **TanStack Query over SWR**: Better devtools, prefetching, cache invalidation. All backend data flows through typed Query hooks with stale-while-revalidate.
- **shadcn/ui over Material UI / Ant Design**: Copy-paste components, full control, no bundle bloat. Fits the operational dashboard aesthetic — clean, data-dense, no unnecessary decoration.
- **Recharts for charts**: Lightweight, React-native, composable. No D3 overhead. Sufficient for margin trend lines and gauges.
- **React Router v6 with `createBrowserRouter`**: Each view is a standalone route. Sidebar navigation drives routing.
- **Native `fetch` over Axios**: One less dependency. The base client is a thin wrapper for base URL, error handling (toast on 4xx/5xx), and JSON parsing.
- **No auth in Phase 1**: Single operator, internal tool. Auth deferred to Phase 2.
- **TypeScript strict mode**: Catches null/undefined issues at compile time. All API responses are fully typed.

## Backend DTO → TypeScript Interface Mapping

All TypeScript interfaces match the exact field names and types from the backend DTOs:

```typescript
// Catalog
interface SkuResponse {
  id: string           // UUID
  name: string
  category: string
  currentState: string // Ideation|ValidationPending|CostGating|StressTesting|Listed|Paused|Scaled|Terminated
  terminationReason: string | null
  createdAt: string    // Instant → ISO-8601
  updatedAt: string
}

interface CostEnvelopeResponse {
  skuId: string
  currency: string
  supplierUnitCost: number     // BigDecimal
  inboundShipping: number
  outboundShipping: number
  platformFee: number
  processingFee: number
  packagingCost: number
  returnHandlingCost: number
  customerAcquisitionCost: number
  warehousingCost: number
  customerServiceCost: number
  refundAllowance: number
  chargebackAllowance: number
  taxesAndDuties: number
  fullyBurdened: number
  verifiedAt: string
}

interface StressTestResponse {
  skuId: string
  passed: boolean
  grossMarginPercent: number
  netMarginPercent: number
  stressedTotalCost: number
  estimatedPrice: number
  stressedShipping: number
  stressedCac: number
  stressedSupplier: number
  stressedRefund: number
  stressedChargeback: number
  currency: string
}

// Pricing
interface PricingResponse {
  skuId: string
  currency: string
  currentPrice: number
  currentMarginPercent: number
  updatedAt: string
  history: PricingHistoryEntry[]
}

interface PricingHistoryEntry {
  price: number
  marginPercent: number
  signalType: string   // SHIPPING_COST_CHANGED|VENDOR_COST_CHANGED|CAC_CHANGED|PLATFORM_FEE_CHANGED|INITIAL
  decisionType: string // ADJUSTED|PAUSE_REQUIRED|TERMINATE_REQUIRED
  decisionReason: string | null
  recordedAt: string
}

// Vendor
interface VendorResponse {
  id: string
  name: string
  contactEmail: string
  status: string
  checklist: ChecklistResponse
  createdAt: string
  updatedAt: string
}

interface ChecklistResponse {
  slaConfirmed: boolean
  defectRateDocumented: boolean
  scalabilityConfirmed: boolean
  fulfillmentTimesConfirmed: boolean
  refundPolicyConfirmed: boolean
}

interface VendorScoreResponse {
  overallScore: number
  onTimeRate: number
  defectRate: number
  breachCount: number
  avgResponseTimeHours: number
}

// Capital
interface ReserveResponse {
  balanceAmount: string
  balanceCurrency: string
  health: string       // HEALTHY|CRITICAL
}

interface SkuPnlResponse {
  skuId: string
  from: string
  to: string
  totalRevenueAmount: string
  totalRevenueCurrency: string
  totalCostAmount: string
  totalCostCurrency: string
  averageGrossMarginPercent: string
  averageNetMarginPercent: string
  snapshotCount: number
}

// NEW — margin history (to be added to backend)
interface MarginSnapshotResponse {
  snapshotDate: string
  grossMarginPercent: number
  netMarginPercent: number
  refundRate: number
  chargebackRate: number
}

// Portfolio
interface PortfolioSummaryResponse {
  totalExperiments: number
  activeExperiments: number
  activeSkus: number
  terminatedSkus: number
  blendedNetMargin: number
  totalProfit: number
}

interface ExperimentResponse {
  id: string
  name: string
  hypothesis: string
  sourceSignal: string | null
  estimatedMarginPerUnit: number | null
  estimatedMarginCurrency: string | null
  validationWindowDays: number
  status: string       // ACTIVE|VALIDATED|FAILED|LAUNCHED|TERMINATED
  launchedSkuId: string | null
  createdAt: string
}

interface KillRecommendationResponse {
  id: string
  skuId: string
  daysNegative: number
  avgNetMargin: number
  detectedAt: string
  confirmedAt: string | null
}

interface PriorityRankingResponse {
  skuId: string
  avgNetMargin: number
  revenueVolume: number
  riskFactor: number
  riskAdjustedReturn: number
}

interface RefundAlertResponse {
  skuIds: string[]
  portfolioAvgRefundRate: number
  elevatedSkuCount: number
}

// Compliance
interface ComplianceStatusResponse {
  skuId: string
  latestResult: string
  latestReason: string | null
  auditHistory: AuditEntry[]
}

interface AuditEntry {
  checkType: string    // IP_CHECK|CLAIMS_CHECK|PROCESSOR_CHECK|SOURCING_CHECK
  result: string       // CLEARED|FAILED
  reason: string | null
  detail: string | null
  checkedAt: string
}

// Fulfillment
interface OrderResponse {
  id: string
  skuId: string
  vendorId: string
  customerId: string
  totalAmount: string
  totalCurrency: string
  status: string       // PENDING|CONFIRMED|SHIPPED|DELIVERED|REFUNDED
  trackingNumber: string | null
  carrier: string | null
  estimatedDelivery: string | null
  createdAt: string
  updatedAt: string
}

interface TrackingResponse {
  orderId: string
  trackingNumber: string | null
  carrier: string | null
  estimatedDelivery: string | null
  lastKnownLocation: string | null
  delayDetected: boolean
  status: string
}

// NEW — state history (to be added to backend)
interface SkuStateHistoryEntry {
  fromState: string
  toState: string
  transitionedAt: string
}
```

## Layer-by-Layer Implementation

### Config Layer
- Initialize Vite + React + TypeScript project
- Install and configure shadcn/ui (with Tailwind CSS)
- Install TanStack Query, React Router v6, Recharts
- Configure `vite.config.ts` with API proxy to `http://localhost:8080`
- Enable TypeScript strict mode
- Add CORS configuration to Spring Boot backend

### Backend API Additions
- Add `GET /api/capital/skus/{id}/margin-history` endpoint to CapitalController
  - Query params: `from` (LocalDate), `to` (LocalDate)
  - Returns `List<MarginSnapshotResponse>` from `MarginSnapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc()`
- Add `GET /api/skus/{id}/state-history` endpoint to SkuController
  - Returns `List<SkuStateHistoryEntry>` from `SkuStateHistoryRepository`

### API / Proxy Layer (frontend)
- `client.ts`: fetch wrapper with base URL from `VITE_API_BASE_URL`, error handling (toast), JSON parsing
- `types.ts`: all TypeScript interfaces (see mapping above)
- `skus.ts`: `useSkus(state?)`, `useSku(id)`, `useSkuStateHistory(id)`, `useVerifyCosts()`, `useRunStressTest()`, `useTransitionSku()`
- `pricing.ts`: `useSkuPricing(id)`
- `vendors.ts`: `useVendors()`, `useVendor(id)`, `useComputeVendorScore()`, `useRegisterVendor()`, `useUpdateChecklist()`, `useActivateVendor()`
- `capital.ts`: `useCapitalReserve()`, `useSkuPnl(id, from, to)`, `useMarginHistory(id, from, to)`
- `portfolio.ts`: `usePortfolioSummary()`, `useExperiments()`, `useCreateExperiment()`, `useValidateExperiment()`, `useFailExperiment()`, `usePriorityRanking()`, `useKillRecommendations()`, `useConfirmKill()`, `useRefundAlerts()`
- `compliance.ts`: `useComplianceStatus(id)`, `useRunComplianceCheck()`
- `orders.ts`: `useOrder(id)`, `useOrderTracking(id)`

### Handler / Page Layer

**SkuPortfolioPage** — Solo spec view: "SKU Portfolio"
- Table of all SKUs with columns: name, category, state (StatusBadge), margin, created date
- State filter dropdown (All, Ideation, Listed, Paused, Scaled, Terminated, etc.)
- Click row → navigate to SkuDetailPage
- Actions column: Pause, Terminate buttons (trigger state transitions)

**SkuDetailPage** — Tabbed detail view
- **Overview tab**: SKU info, current state badge, state history timeline
- **Cost Breakdown tab**: CostBreakdownTable showing all 13 components + fully burdened total
- **Stress Test tab**: StressTestResultCard with pass/fail, stressed amounts, margins
- **Pricing tab**: current price, margin %, pricing history chart (Recharts LineChart)
- **P&L tab**: revenue, cost, gross/net margin from SkuPnlResponse
- **Compliance tab**: latest result, audit history table

**CostGateRunnerPage** — Solo spec view: "Cost Gate Runner"
- Step 1: Select existing SKU (or create new one) in CostGating state
- Step 2: Form to input cost verification parameters (VerifyCostsRequest fields)
  - Vendor quote (amount + currency), package dimensions, origin/destination addresses
  - CAC estimate, jurisdiction, warehouse/service/packaging/return costs
  - Refund/chargeback allowance rates, taxes/duties, estimated order value
- Step 3: Submit → POST `/api/skus/{id}/verify-costs` → display CostEnvelopeResponse
- Step 4: Run stress test → input estimated price → POST `/api/skus/{id}/stress-test`
- Step 5: Display StressTestResponse (pass/fail). If passed → approve to launch. If failed → terminate.

**VendorScorecardPage** — Solo spec view: "Vendor Scorecard"
- Table of vendors with columns: name, status (StatusBadge), email, SLA confirmed, created date
- Expandable row or click → vendor detail with reliability score breakdown (VendorScoreBar)
- Score components: on-time rate, defect rate, breach count, avg response time
- Actions: Pause vendor (set inactive), view linked SKUs

**MarginMonitorPage** — Solo spec view: "Margin Monitor"
- Portfolio-level blended margin (from PortfolioSummaryResponse.blendedNetMargin)
- SKU selector dropdown → per-SKU 90-day margin trend chart (MarginTrendChart)
  - Uses `GET /api/capital/skus/{id}/margin-history?from=90daysAgo&to=today`
  - Line chart: grossMarginPercent + netMarginPercent over time
  - Horizontal reference lines at 50% (gross floor) and 30% (net floor)
- Refund rate and chargeback rate overlay from margin snapshots
- Drill-down: click data point → navigate to SkuDetailPage P&L tab

**ExperimentTrackerPage** — Solo spec view: "Experiment Tracker"
- Table of experiments: name, hypothesis, source signal, estimated margin/unit, status, validation window
- Validation window countdown: `createdAt + validationWindowDays - now` → days remaining
- Actions: Mark Validated (link to SKU), Mark Failed
- Create new experiment button → form with CreateExperimentRequest fields
- No budget columns — zero-capital model

**CapitalOverviewPage** — Solo spec view: "Capital Overview"
- ReserveGauge: current reserve balance + health status (HEALTHY/CRITICAL)
- SKU-level P&L table: select SKU + date range → SkuPnlResponse data
- Priority ranking table from `GET /api/portfolio/reallocation`
  - Columns: SKU ID, avg net margin, revenue volume, risk factor, risk-adjusted return
  - Sorted by risk-adjusted return descending
- Refund alerts section from `GET /api/portfolio/refund-alerts`

**DemandSignalsPage** — Solo spec view: "Demand Signals" (placeholder)
- Placeholder card explaining DemandScanJob is not yet implemented
- Describes what it will show: Google Trends, Reddit, Amazon PA-API trending categories
- Link to create a new experiment manually as a workaround

**KillLogPage** — Solo spec view: "Kill Log"
- Table of terminated SKUs (filtered from `GET /api/skus?state=Terminated`)
  - Columns: name, termination reason, terminated date (from state history)
- Table of confirmed kill recommendations from `GET /api/portfolio/kill-recommendations`
  - Filter to show confirmed (confirmedAt != null)
  - Columns: SKU ID, days negative, avg net margin, detected at, confirmed at
- Pattern analysis: group terminations by reason, show counts

**ComplianceStatusPage** — Additional view
- SKU selector → show ComplianceStatusResponse
- Latest result badge (CLEARED/FAILED) with reason
- Audit history table: checkType, result, reason, detail, checkedAt
- Action: trigger manual compliance check (POST)

### Common Components
- `StatusBadge`: color-coded labels for SKU states, experiment statuses, compliance results
- `MarginTrendChart`: Recharts LineChart with margin reference lines at 50%/30%
- `ReserveGauge`: visual bar showing reserve balance relative to HEALTHY/CRITICAL threshold
- `VendorScoreBar`: 0–100 score visualized as a colored progress bar
- `CostBreakdownTable`: 13-row table of cost components + fully burdened total
- `StressTestResultCard`: pass/fail card with stressed amounts and margin breakdown
- `KpiCard`: summary metric card (label, value, trend indicator)
- `DataTable`: reusable sortable/filterable table built on shadcn/ui Table
- `AppShell`: sidebar + main content layout
- `Sidebar`: navigation links for all 9 views + active state highlighting
- Loading skeletons, error boundaries, empty states for all data-fetching pages

## Task Breakdown

### Config Layer
- [x] Initialize Vite + React + TypeScript project in `frontend/`
- [x] Install and configure Tailwind CSS
- [x] Install and configure shadcn/ui (`npx shadcn@latest init`)
- [x] Install shadcn/ui components: Button, Card, Table, Badge, Input, Select, Tabs, Skeleton, Separator, DropdownMenu, Dialog, Label, Textarea, Progress, Sonner
- [x] Install TanStack Query (`@tanstack/react-query`, `@tanstack/react-query-devtools`)
- [x] Install React Router v6 (`react-router-dom`)
- [x] Install Recharts
- [x] Configure `vite.config.ts` with API proxy to `http://localhost:8080`
- [x] Enable TypeScript strict mode in `tsconfig.json`

### Backend API Layer
- [x] Add CORS configuration to Spring Boot backend (WebMvcConfigurer allowing `http://localhost:5173`)
- [x] Add `GET /api/capital/skus/{id}/margin-history` endpoint to CapitalController
- [x] Add `GET /api/skus/{id}/state-history` endpoint to SkuController
- [x] Add `MarginSnapshotResponse` DTO
- [x] Add `SkuStateHistoryResponse` DTO

### API / Proxy Layer (frontend)
- [x] Implement `src/api/client.ts` — fetch wrapper with base URL, error toast, JSON parsing
- [x] Implement `src/api/types.ts` — all TypeScript interfaces matching backend DTOs
- [x] Implement `src/api/skus.ts` — hooks: `useSkus`, `useSku`, `useSkuStateHistory`, `useVerifyCosts`, `useRunStressTest`, `useTransitionSku`
- [x] Implement `src/api/pricing.ts` — hooks: `useSkuPricing`
- [x] Implement `src/api/vendors.ts` — hooks: `useVendors`, `useVendor`, `useComputeVendorScore`, `useRegisterVendor`, `useUpdateChecklist`, `useActivateVendor`
- [x] Implement `src/api/capital.ts` — hooks: `useCapitalReserve`, `useSkuPnl`, `useMarginHistory`
- [x] Implement `src/api/portfolio.ts` — hooks: `usePortfolioSummary`, `useExperiments`, `useCreateExperiment`, `useValidateExperiment`, `useFailExperiment`, `usePriorityRanking`, `useKillRecommendations`, `useConfirmKill`, `useRefundAlerts`
- [x] Implement `src/api/compliance.ts` — hooks: `useComplianceStatus`, `useRunComplianceCheck`
- [x] Implement `src/api/orders.ts` — hooks: `useOrder`, `useOrderTracking`

### Common Components
- [x] Implement `AppShell` layout component (sidebar + main content area)
- [x] Implement `Sidebar` navigation with links for all 9 views
- [x] Implement `StatusBadge` component (SKU states, experiment statuses, compliance results)
- [x] Implement `KpiCard` component (label, value, optional trend)
- [x] Implement `DataTable` reusable component (sortable, filterable)
- [x] Implement `MarginTrendChart` using Recharts ComposedChart (with 50%/30% reference lines)
- [x] Implement `ReserveGauge` component (balance vs threshold)
- [x] Implement `VendorScoreBar` component (0–100 progress bar)
- [x] Implement `CostBreakdownTable` component (13 cost components + total)
- [x] Implement `StressTestResultCard` component (pass/fail + stressed amounts)
- [x] Implement `src/lib/formatters.ts` (money, percentage, date formatting utilities)
- [x] Add loading skeleton states for all data-fetching components
- [x] Add error boundary and empty state handling

### Handler / Page Layer
- [x] Implement `App.tsx` with `createBrowserRouter` and route definitions
- [x] Implement `SkuPortfolioPage` — SKU table with state filter, actions
- [x] Implement `SkuDetailPage` — tabbed view (overview, cost, stress test, pricing, P&L, compliance)
- [x] Implement `CostGateRunnerPage` — multi-step form (select SKU → verify costs → stress test → approve/reject)
- [x] Implement `VendorScorecardPage` — vendor table with score breakdown
- [x] Implement `MarginMonitorPage` — portfolio margin + per-SKU 90-day chart
- [x] Implement `ExperimentTrackerPage` — experiment table with countdown, create form
- [x] Implement `CapitalOverviewPage` — reserve gauge, P&L table, priority ranking, refund alerts
- [x] Implement `DemandSignalsPage` — placeholder with explanation
- [x] Implement `KillLogPage` — terminated SKUs + confirmed kills with reasons
- [x] Implement `ComplianceStatusPage` — per-SKU audit trail with manual check trigger

## Testing Strategy

- TypeScript strict compile: `npm run build` produces no errors
- Component smoke tests with React Testing Library for key pages
- MSW (Mock Service Worker) for API mocking in tests — mock all 29 endpoints
- Visual verification: each of the 9 views renders correctly with mock data
- Error state testing: verify error boundaries display on API failures
- Empty state testing: verify empty state messages when no data exists

## Rollout Plan

1. Initialize Vite project, install all dependencies, configure build tooling
2. Add CORS config + 2 new backend endpoints
3. Implement API client, types, and all TanStack Query hooks
4. Build app shell (sidebar + routing) and common components
5. Implement all 9 pages
6. Run `npm run build` — verify zero TypeScript errors
7. Manual smoke test all views against running backend
