# FR-012: Frontend Dashboard — Implementation Plan

## Technical Design

A React + Vite + TypeScript application using shadcn/ui for components and TanStack Query for data fetching. The app is a read-heavy internal tool with five main sections: Portfolio, SKUs, Vendors, Capital, and Experiments. All data is fetched from the Spring Boot backend REST API.

```
frontend/
├── src/
│   ├── main.tsx
│   ├── App.tsx                    (router setup)
│   ├── api/
│   │   ├── client.ts              (Axios base client)
│   │   ├── skus.ts
│   │   ├── vendors.ts
│   │   ├── capital.ts
│   │   └── portfolio.ts
│   ├── pages/
│   │   ├── PortfolioPage.tsx
│   │   ├── SkuListPage.tsx
│   │   ├── SkuDetailPage.tsx
│   │   ├── VendorListPage.tsx
│   │   ├── CapitalPage.tsx
│   │   └── ExperimentsPage.tsx
│   ├── components/
│   │   ├── SkuCard.tsx
│   │   ├── VendorScoreBar.tsx
│   │   ├── MarginTrendChart.tsx
│   │   ├── ReserveGauge.tsx
│   │   └── StatusBadge.tsx
│   └── lib/
│       └── utils.ts               (shadcn/ui util)
├── index.html
├── vite.config.ts
├── tsconfig.json
└── package.json
```

## Architecture Decisions

- **TanStack Query over SWR or manual fetch**: Better devtools, prefetching, and cache invalidation primitives. All backend data goes through Query hooks.
- **shadcn/ui over a component library**: Copy-paste components give full control over styling without fighting a library's opinions. Fits the operational dashboard aesthetic.
- **Recharts for margin trend charts**: Lightweight, React-native, composable. No D3 overhead.
- **React Router v6 for routing**: File-based routing via `createBrowserRouter`. Each page is a standalone route.
- **Axios base client with interceptors**: Centralizes base URL, error handling (toast on 4xx/5xx), and future auth header injection.
- **No auth in Phase 1**: The dashboard is an internal tool. Auth added in Phase 2.

## Layer-by-Layer Implementation

### Frontend Routing & Shell
- `App.tsx`: `createBrowserRouter` with sidebar nav (Portfolio, SKUs, Vendors, Capital, Experiments)
- Sidebar uses shadcn/ui `NavigationMenu`

### API Layer (`src/api/`)
- `client.ts`: Axios instance with `baseURL = VITE_API_BASE_URL`, error interceptor
- One file per domain: typed request/response interfaces, TanStack Query hooks (`useSkus`, `useSku`, `useVendors`, `useCapitalReserve`, `usePortfolioSummary`)

### Pages
- `PortfolioPage`: summary cards (total experiments, active SKUs, blended margin, capital deployed) + experiment table
- `SkuListPage`: table with state filter dropdown, status badge, link to detail
- `SkuDetailPage`: tabs for cost breakdown, stress test result, pricing history chart, P&L summary
- `VendorListPage`: table with reliability score bar, breach count, linked SKUs
- `CapitalPage`: reserve gauge, margin trend chart (last 90 days), shutdown rule status table
- `ExperimentsPage`: experiment list with budget, window countdown, status

### Components
- `MarginTrendChart`: Recharts `LineChart` for 90-day margin trend
- `ReserveGauge`: visual indicator of reserve balance vs. target range
- `StatusBadge`: color-coded SKU/experiment status label
- `VendorScoreBar`: 0–100 score visualized as a progress bar

## Task Breakdown

### Config Layer
- [ ] Initialize Vite + React + TypeScript project (`npm create vite@latest frontend -- --template react-ts`)
- [ ] Install shadcn/ui and run `npx shadcn-ui@latest init`
- [ ] Install TanStack Query, React Router v6, Axios, Recharts
- [ ] Configure `vite.config.ts` with API proxy to `http://localhost:8080`
- [ ] Enable TypeScript strict mode in `tsconfig.json`
- [ ] Configure CORS on Spring Boot backend to allow `http://localhost:5173`

### API / Proxy Layer
- [ ] Implement `src/api/client.ts` with Axios base client and error interceptor
- [ ] Implement `src/api/skus.ts` with hooks: `useSkus(state?)`, `useSku(id)`, `useSkuPnl(id, from, to)`, `useSkuPricing(id)`, `useSkuCompliance(id)`
- [ ] Implement `src/api/vendors.ts` with hooks: `useVendors()`, `useVendor(id)`, `useVendorScore(id)`
- [ ] Implement `src/api/capital.ts` with hooks: `useCapitalReserve()`, `useSkuMarginHistory(id)`
- [ ] Implement `src/api/portfolio.ts` with hooks: `usePortfolioSummary()`, `useExperiments()`, `useReallocationRecommendation()`

### Handler / Page Layer
- [ ] Implement app shell with sidebar navigation (`App.tsx`, `Layout.tsx`)
- [ ] Implement `PortfolioPage` with summary KPI cards and experiment table
- [ ] Implement `SkuListPage` with state filter and paginated SKU table
- [ ] Implement `SkuDetailPage` with cost breakdown, stress test, pricing history, P&L
- [ ] Implement `VendorListPage` with reliability scores and breach history
- [ ] Implement `CapitalPage` with reserve gauge and margin trend chart
- [ ] Implement `ExperimentsPage` with experiment table and budget tracking

### Common Components
- [ ] Implement `StatusBadge` component for SKU/experiment states
- [ ] Implement `MarginTrendChart` using Recharts LineChart
- [ ] Implement `ReserveGauge` component
- [ ] Implement `VendorScoreBar` component
- [ ] Add loading skeleton states for all data-fetching pages
- [ ] Add error boundary and empty state handling for all pages

## Testing Strategy

- Unit test: `BackwardInductionPricer` hook calculations (component-level)
- React Testing Library: `SkuListPage` renders SKUs, applies state filter
- React Testing Library: `SkuDetailPage` renders all tabs without errors
- MSW (Mock Service Worker) for API mocking in tests
- Visual smoke test: `npm run build` produces no TypeScript errors

## Rollout Plan

1. Initialize Vite project and install dependencies
2. Configure API proxy and CORS
3. Implement API layer with TanStack Query hooks (using MSW for mocks)
4. Build app shell and navigation
5. Implement all 6 pages with shadcn/ui components
6. Implement shared chart and gauge components
7. Run `npm run build` and verify no errors
