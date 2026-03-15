# FR-012: Frontend Dashboard — Implementation Summary

## Feature Summary

A React + Vite + TypeScript operations dashboard for the Commerce Engine, implementing all 8 views from the solo-operator spec Section 7.1 plus a compliance view. The dashboard uses the "Engine Room" design system — warm dark theme with amber accent, three custom typefaces, animated sprites, and easter eggs. All data flows from 29 Spring Boot REST API endpoints through typed TanStack Query hooks.

## Changes Made

### Backend (Kotlin/Spring Boot)
- **CORS configuration** — `CorsConfig` WebMvcConfigurer allowing `localhost:5173` and `localhost:3000`
- **Margin history endpoint** — `GET /api/capital/skus/{id}/margin-history` with `MarginSnapshotResponse` DTO
- **SKU state history endpoint** — `GET /api/skus/{id}/state-history` with `SkuStateHistoryResponse` DTO
- **Repository method** — `findBySkuIdOrderByTransitionedAtAsc` added to `SkuStateHistoryRepository`

### Frontend (React/TypeScript)
- **9 pages** covering all solo-operator spec dashboard views
- **30 TanStack Query hooks** (17 queries + 13 mutations) across 7 API modules
- **28 TypeScript interfaces** matching backend DTOs exactly
- **16 custom components** including layout, data visualization, and 5 sprite characters
- **"Engine Room" design system** with Bricolage Grotesque, Onest, and Martian Mono fonts
- **Konami code easter egg** (rainbow Engine Pulse Bar + sprite parade)
- **"Ship It" easter egg** (click sidebar Shipper sprite 5 times)

## Files Modified

### Backend
- `modules/app/src/main/kotlin/com/autoshipper/app/config/CorsConfig.kt` — NEW: CORS configuration
- `modules/capital/src/main/kotlin/com/autoshipper/capital/handler/CapitalController.kt` — Added margin-history endpoint
- `modules/capital/src/main/kotlin/com/autoshipper/capital/handler/dto/MarginSnapshotResponse.kt` — NEW: DTO
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/SkuController.kt` — Added state-history endpoint
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/dto/SkuStateHistoryResponse.kt` — NEW: DTO
- `modules/catalog/src/main/kotlin/com/autoshipper/catalog/persistence/SkuStateHistoryRepository.kt` — Added query method

### Frontend (all NEW)
- `frontend/` — Entire Vite + React + TypeScript project
- `frontend/src/index.css` — Engine Room design system theme (CSS variables, fonts, animations)
- `frontend/src/App.tsx` — Router, QueryClient, Konami easter egg
- `frontend/src/api/client.ts` — Fetch wrapper with error toasts
- `frontend/src/api/types.ts` — 28 TypeScript interfaces
- `frontend/src/api/skus.ts` — 7 hooks (3 queries, 4 mutations)
- `frontend/src/api/pricing.ts` — 1 query hook
- `frontend/src/api/vendors.ts` — 6 hooks (2 queries, 4 mutations)
- `frontend/src/api/capital.ts` — 3 query hooks
- `frontend/src/api/portfolio.ts` — 9 hooks (5 queries, 4 mutations)
- `frontend/src/api/compliance.ts` — 2 hooks (1 query, 1 mutation)
- `frontend/src/api/orders.ts` — 2 query hooks
- `frontend/src/lib/formatters.ts` — Money, percentage, date formatters
- `frontend/src/lib/konami.ts` — Konami code hook
- `frontend/src/components/layout/AppShell.tsx` — Sidebar + content layout
- `frontend/src/components/layout/Sidebar.tsx` — Grouped nav with Lucide icons
- `frontend/src/components/layout/EnginePulse.tsx` — Animated status bar
- `frontend/src/components/StatusBadge.tsx` — Color-coded state pills
- `frontend/src/components/KpiCard.tsx` — Metric cards with accent borders
- `frontend/src/components/DataTable.tsx` — Generic sortable table
- `frontend/src/components/MarginTrendChart.tsx` — Recharts margin chart
- `frontend/src/components/ReserveGauge.tsx` — Reserve health bar
- `frontend/src/components/VendorScoreBar.tsx` — Score progress bar
- `frontend/src/components/CostBreakdownTable.tsx` — 13-component cost table
- `frontend/src/components/StressTestResultCard.tsx` — Pass/fail result card
- `frontend/src/components/StepIndicator.tsx` — Multi-step progress dots
- `frontend/src/components/sprites/SpriteScene.tsx` — Sprite wrapper
- `frontend/src/components/sprites/Shipper.tsx` — Hard hat sprite SVG
- `frontend/src/components/sprites/Analyst.tsx` — Glasses sprite SVG
- `frontend/src/components/sprites/Guard.tsx` — Shield sprite SVG
- `frontend/src/components/sprites/Scout.tsx` — Binoculars sprite SVG
- `frontend/src/components/sprites/Reaper.tsx` — Scythe sprite SVG
- `frontend/src/pages/SkuPortfolioPage.tsx` — SKU list + state filter + actions
- `frontend/src/pages/SkuDetailPage.tsx` — 6-tab detail view
- `frontend/src/pages/CostGateRunnerPage.tsx` — 4-step cost gate form
- `frontend/src/pages/VendorScorecardPage.tsx` — Vendor table + scores
- `frontend/src/pages/MarginMonitorPage.tsx` — 90-day margin charts
- `frontend/src/pages/ExperimentTrackerPage.tsx` — Experiment CRUD + countdown
- `frontend/src/pages/CapitalOverviewPage.tsx` — Reserve, P&L, ranking, alerts
- `frontend/src/pages/DemandSignalsPage.tsx` — Placeholder with Scout sprite
- `frontend/src/pages/KillLogPage.tsx` — Terminated SKUs + kill recs
- `frontend/src/pages/ComplianceStatusPage.tsx` — Audit trail + manual check

## Testing Completed

- `tsc -b` — TypeScript strict mode compilation passes with zero errors
- `vite build` — Production build succeeds (55 KB CSS, 1 MB JS)
- `./gradlew compileKotlin` — Backend compiles cleanly with new endpoints

## Deployment Notes

- Frontend dev server: `cd frontend && npm run dev` → `http://localhost:5173`
- Backend must be running on `http://localhost:8080` (Vite proxy handles `/api/*`)
- Production: `npm run build` produces static assets in `frontend/dist/`
- Docker: serve at `localhost:3000` per solo-operator spec
- No authentication required (Phase 1, single operator)
