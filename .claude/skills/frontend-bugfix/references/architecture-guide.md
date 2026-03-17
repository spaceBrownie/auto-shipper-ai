# Frontend Architecture Guide

Quick reference for the Commerce Engine dashboard's architecture, data flow, and design system.

## Table of Contents

1. [Directory Structure](#directory-structure)
2. [Data Flow](#data-flow)
3. [Component Inventory](#component-inventory)
4. [Design Tokens](#design-tokens)
5. [Typography](#typography)
6. [Page Routes](#page-routes)

## Directory Structure

```
frontend/src/
├── api/                 # Data layer — hooks + types (like Spring repositories)
│   ├── client.ts        # Base fetch wrapper (apiGet, apiPost, apiPatch)
│   ├── types.ts         # Response/request DTOs — must match backend API contracts
│   ├── skus.ts          # Catalog module hooks
│   ├── capital.ts       # Capital module hooks
│   ├── compliance.ts    # Compliance module hooks
│   ├── orders.ts        # Fulfillment module hooks
│   ├── portfolio.ts     # Portfolio module hooks
│   ├── pricing.ts       # Pricing module hooks
│   └── vendors.ts       # Vendor module hooks
├── components/          # Reusable UI pieces
│   ├── ui/              # shadcn primitives — NEVER edit directly
│   ├── layout/          # AppShell, Sidebar, EnginePulse
│   ├── sprites/         # Pixel-art character components (easter eggs)
│   ├── KpiCard.tsx      # Summary metric card
│   ├── DataTable.tsx    # Generic typed table
│   ├── StatusBadge.tsx  # Color-coded status indicator
│   ├── CostBreakdownTable.tsx
│   ├── MarginTrendChart.tsx
│   ├── ReserveGauge.tsx
│   ├── StepIndicator.tsx
│   ├── StressTestResultCard.tsx
│   └── VendorScoreBar.tsx
├── pages/               # One file per route
├── lib/                 # Utilities
│   ├── formatters.ts    # formatMoney, formatPercent, formatDate, formatDateTime, daysRemaining
│   ├── utils.ts         # cn() — Tailwind class merger
│   └── konami.ts        # Easter egg hook
├── index.css            # Design system tokens (THE source of truth for visual style)
├── App.tsx              # Router + QueryClient setup
└── main.tsx             # Entry point
```

## Data Flow

```
Backend API  →  api/client.ts  →  api/{module}.ts  →  Page component  →  JSX
                 (fetch)          (React Query)       (renders data)
```

**Reading data:** `useQuery` with a `queryKey` that acts as a cache key.
```tsx
export function useSkus(state?: string) {
  return useQuery({
    queryKey: ["skus", state],         // cache identity
    queryFn: () => apiGet<SkuResponse[]>(`/api/skus`),
  });
}
```

**Writing data:** `useMutation` with `invalidateQueries` to bust the cache.
```tsx
export function useTransitionSku() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }) => apiPost(`/api/skus/${id}/state`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["skus"] });
    },
  });
}
```

**Error handling:** `api/client.ts` automatically shows `toast.error()` for non-OK responses and throws `ApiError`. Page components don't need to handle network errors manually — React Query surfaces them via `isError`.

## Component Inventory

### Layout
| Component | Import | Purpose |
|---|---|---|
| `AppShell` | `@/components/layout/AppShell` | Sidebar + main content wrapper |
| `Sidebar` | `@/components/layout/Sidebar` | Fixed left nav with sections |
| `EnginePulse` | `@/components/layout/EnginePulse` | Animated accent bar at top |

### Data Display
| Component | Import | Purpose |
|---|---|---|
| `KpiCard` | `@/components/KpiCard` | Summary metric with colored left border |
| `DataTable` | `@/components/DataTable` | Generic typed table with hover rows |
| `StatusBadge` | `@/components/StatusBadge` | Semantic color-coded badge |
| `MarginTrendChart` | `@/components/MarginTrendChart` | Recharts line chart for margin data |
| `CostBreakdownTable` | `@/components/CostBreakdownTable` | 13-row cost envelope breakdown |
| `StressTestResultCard` | `@/components/StressTestResultCard` | Pass/fail stress test display |
| `ReserveGauge` | `@/components/ReserveGauge` | Visual reserve health indicator |
| `VendorScoreBar` | `@/components/VendorScoreBar` | Vendor score visualization |
| `StepIndicator` | `@/components/StepIndicator` | Multi-step progress indicator |

### Fun
| Component | Import | Purpose |
|---|---|---|
| `SpriteScene` | `@/components/sprites/SpriteScene` | Empty state wrapper with message |
| `Scout`, `Shipper`, `Analyst`, `Guard`, `Reaper` | `@/components/sprites/*` | Pixel-art character SVGs |

### shadcn Primitives (in `components/ui/`)
Badge, Button, Card, Dialog, DropdownMenu, Input, Label, Progress, Select, Separator, Skeleton, Sonner (toast), Table, Tabs, Textarea

## Design Tokens

All values come from CSS variables in `frontend/src/index.css`. Never hardcode colors or fonts.

**SVG exception:** Recharts SVG elements (`stopColor`, `stroke`) cannot resolve CSS `var()` references. Hardcoded hex values matching the tokens below are acceptable inside Recharts chart components.

### Colors
| Variable | Hex | Usage |
|---|---|---|
| `--bg-root` | `#0c0c0e` | Page background (warm near-black) |
| `--bg-surface-1` | `#141416` | Cards, sidebar, elevated surfaces |
| `--bg-surface-2` | `#1c1c20` | Input backgrounds, secondary surfaces |
| `--bg-surface-3` | `#24242a` | Hover states, tertiary surfaces |
| `--border-default` | `#2a2a32` | Standard borders |
| `--border-bright` | `#3a3a44` | Emphasized borders |
| `--text-primary` | `#ececf0` | Main text |
| `--text-secondary` | `#8888a0` | Labels, metadata |
| `--text-tertiary` | `#555566` | Disabled, placeholder |
| `--accent` | `#e5a00d` | Warm amber/gold (the signature color) |
| `--accent-dim` | `rgba(229,160,13,0.12)` | Accent background tint |
| `--profit` | `#34d399` | Positive/healthy states |
| `--profit-dim` | `rgba(52,211,153,0.12)` | Profit background tint |
| `--warning` | `#fbbf24` | Caution states |
| `--warning-dim` | `rgba(251,191,36,0.12)` | Warning background tint |
| `--danger` | `#f87171` | Critical/negative states |
| `--danger-dim` | `rgba(248,113,113,0.12)` | Danger background tint |
| `--info` | `#60a5fa` | Informational states |
| `--info-dim` | `rgba(96,165,250,0.12)` | Info background tint |

### Semantic Color Mapping
| Status | Color | Example |
|---|---|---|
| Listed, Scaled, ACTIVE, HEALTHY, CLEARED | `--profit` | Good states |
| Ideation, ValidationPending | `--info` | Early pipeline |
| CostGating, StressTesting, Paused | `--warning` | In-progress or paused |
| Terminated, FAILED, CRITICAL | `--danger` | Bad states |

## Typography

Three fonts, each with a specific role:

| Font | Variable | Role | Example Usage |
|---|---|---|---|
| `Bricolage Grotesque` | `--font-display` | Page titles, section headings | `fontSize: 28, fontWeight: 700` |
| `Onest` | `--font-sans` | Body text, labels, UI chrome | `fontSize: 14, fontWeight: 400-500` |
| `Martian Mono` | `--font-mono` | Numeric data, dates, money | `fontSize: 12-36, fontWeight: 400-500` |

### Type Scale (from CSS variables)
| Variable | Size | Usage |
|---|---|---|
| `--text-page-title` | 28px | Page headings |
| `--text-section-title` | 20px | Card titles, section headers |
| `--text-kpi-value` | 36px | Large KPI numbers |
| `--text-table-header` | 12px | Table column headers (uppercase) |
| `--text-body` | 14px | Body text, table cells |
| `--text-label` | 12px | Labels, metadata |
| `--text-data` | 14px | Data values |
| `--text-small-data` | 12px | Small data, timestamps |

## Page Routes

| Route | Page | Sidebar Section |
|---|---|---|
| `/skus` | SkuPortfolioPage | OPERATE |
| `/skus/:id` | SkuDetailPage | (detail view) |
| `/cost-gate` | CostGateRunnerPage | OPERATE |
| `/margin` | MarginMonitorPage | INTELLIGENCE |
| `/experiments` | ExperimentTrackerPage | INTELLIGENCE |
| `/demand` | DemandSignalsPage | INTELLIGENCE |
| `/vendors` | VendorScorecardPage | INFRASTRUCTURE |
| `/capital` | CapitalOverviewPage | INFRASTRUCTURE |
| `/compliance` | ComplianceStatusPage | INFRASTRUCTURE |
| `/kill-log` | KillLogPage | HISTORY |
