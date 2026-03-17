---
name: frontend-build
description: >
  Build new pages, components, and UI features within the Commerce Engine dashboard's established
  design system (React + Vite + shadcn/ui, dark theme, warm amber/gold accent). Use this skill
  when the user asks to add a new page, create a new component, modify an existing page's layout,
  add charts or data displays, or build any new frontend UI. Also triggers for: "add a page for",
  "create a component that", "build a dashboard view", "show this data in the frontend",
  "add a table/chart/card for", "wire up the frontend to this API", or any request to display
  backend data in the UI. This skill ensures visual consistency by enforcing the project's design
  tokens, typography rules, and component patterns — the operator doesn't need design taste,
  just needs to follow the system.
---

# Frontend Build

A protocol for building new UI within the Commerce Engine dashboard's established design system. This skill exists because the operator is a backend engineer who shouldn't need to make design decisions — the design system is already decided. This skill tells you which token to use where, which component to reach for, and which patterns to follow.

## The Core Idea

This project has a fully established visual identity: dark theme, warm amber/gold accent, three specific fonts, semantic color system. Every new piece of UI must fit this identity. The skill's job is to make that automatic by constraining choices to the existing token set and component patterns. Think of it like coding against an interface — you implement the pattern, you don't redesign it.

## Before You Start

Read the design system reference for the full token set and page anatomy:
```
Read: .claude/skills/frontend-build/references/design-system.md
```

This reference contains every color, font, spacing value, and the standard page structure. Consult it whenever you're making a visual decision.

## Step 1: Understand What You're Building

Before writing any code, classify the work:

| Type | Examples | What You Need |
|---|---|---|
| **New page** | "Add an orders page" | Route in App.tsx, sidebar entry, page file, API hooks |
| **New component** | "Add a progress ring" | Component file in `components/`, typed props interface |
| **Page modification** | "Add a chart to the margin page" | Modify existing page, possibly new component |
| **API integration** | "Wire up the new endpoint" | Hook in `api/`, types in `api/types.ts` |

## Step 2: Check What Already Exists

Before building anything new, check the existing component inventory. The project likely already has what you need or something close to it.

**Reusable components to check first:**
- `KpiCard` — summary metric with colored left border (label + big number + optional trend)
- `DataTable` — generic typed table with column definitions and row click
- `StatusBadge` — color-coded pill for entity states
- `MarginTrendChart` — Recharts line chart (reusable for any time-series)
- `CostBreakdownTable` — specialized for cost envelope data
- `StressTestResultCard` — pass/fail display
- `ReserveGauge` — visual gauge for reserve health
- `VendorScoreBar` — horizontal score bar
- `StepIndicator` — multi-step progress
- `SpriteScene` — empty state wrapper with character and message

**shadcn primitives (in `components/ui/`):**
Badge, Button, Card (Card, CardHeader, CardTitle, CardContent), Dialog, DropdownMenu, Input, Label, Progress, Select, Separator, Skeleton, Sonner (toast), Table, Tabs, Textarea

**Utility functions (in `lib/formatters.ts`):**
- `formatMoney(amount, currency)` — "$12,450.00"
- `formatPercent(value)` — "54.2%"
- `formatDate(isoString)` — "Mar 12, 2026"
- `formatDateTime(isoString)` — "Mar 12, 2026 2:30 PM"
- `daysRemaining(createdAt, windowDays)` — number of days left

**Icons:** This project uses `lucide-react`. Check their icon set before creating custom SVGs.

## Step 3: Build the API Layer First

If the new UI needs data from the backend, build the data layer before the UI. This mirrors the backend-first thinking the operator is familiar with.

### 3a. Define Types

Add request/response types to `frontend/src/api/types.ts`. These must exactly match the backend API contracts:

```tsx
// Follow the existing naming pattern: {Entity}Response, {Action}{Entity}Request
export interface OrderResponse {
  id: string;
  skuId: string;
  status: string;
  // ... match the backend DTO exactly
}
```

### 3b. Create Hooks

Create or update the appropriate `api/{module}.ts` file. Follow this exact pattern:

```tsx
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiGet, apiPost } from "@/api/client";
import type { OrderResponse } from "@/api/types";

// Queries — for reading data
export function useOrders() {
  return useQuery({
    queryKey: ["orders"],
    queryFn: () => apiGet<OrderResponse[]>("/api/orders"),
  });
}

// Queries with params — use the param in the queryKey for cache separation
export function useOrder(id: string) {
  return useQuery({
    queryKey: ["orders", id],
    queryFn: () => apiGet<OrderResponse>(`/api/orders/${id}`),
    enabled: !!id,  // don't fetch until id is available
  });
}

// Mutations — for writing data
export function useCreateOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateOrderRequest) =>
      apiPost<OrderResponse>("/api/orders", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["orders"] });
    },
  });
}
```

**Rules:**
- One file per backend module (catalog → skus.ts, fulfillment → orders.ts, etc.)
- Query keys follow a hierarchy: `["entity"]`, `["entity", id]`, `["entity", id, "sub-resource"]`
- Always invalidate relevant queries in mutation `onSuccess`
- Always add `enabled: !!id` when the query depends on a selected item
- Never call `fetch()` directly — always go through `apiGet`/`apiPost`/`apiPatch`

## Step 4: Build the UI

### Page Structure

Every page in this project follows the same anatomy. This is the template:

```tsx
import { Skeleton } from "@/components/ui/skeleton";
import { KpiCard } from "@/components/KpiCard";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Shipper } from "@/components/sprites/Shipper";  // or another sprite

export default function NewFeaturePage() {
  const { data, isLoading } = useNewFeatureData();

  // 1. Loading state — always first
  if (isLoading) {
    return (
      <div>
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
            marginBottom: 24,
          }}
        >
          Page Title
        </h1>
        <div className="grid grid-cols-4 gap-4 mb-6">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
        <Skeleton className="h-64 rounded-lg" />
      </div>
    );
  }

  // 2. Empty state — when data loads but there's nothing
  if (!data || data.length === 0) {
    return (
      <div>
        <h1 style={{ /* same heading styles */ }}>Page Title</h1>
        <div className="flex flex-col items-center justify-center py-20">
          <SpriteScene
            message="No data yet. Something descriptive!"
            animation="idle"
          >
            <Shipper size={48} />
          </SpriteScene>
        </div>
      </div>
    );
  }

  // 3. Populated state — the actual content
  return (
    <div>
      {/* Page heading */}
      <h1
        style={{
          fontFamily: "'Bricolage Grotesque', sans-serif",
          fontWeight: 700,
          fontSize: 28,
          lineHeight: 1.2,
          color: "var(--text-primary)",
          marginBottom: 24,
        }}
      >
        Page Title
      </h1>

      {/* KPI cards (if applicable) */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <KpiCard label="Metric" value={42} accentColor="info" />
      </div>

      {/* Main content */}
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        {/* DataTable, chart, or custom content */}
      </div>
    </div>
  );
}
```

### Visual Rules

These rules come from the established design system. Follow them exactly.

**Headings:**
```tsx
// Page title (h1)
style={{
  fontFamily: "'Bricolage Grotesque', sans-serif",
  fontWeight: 700,
  fontSize: 28,
  lineHeight: 1.2,
  color: "var(--text-primary)",
}}

// Section title (card header, subsection)
style={{
  fontFamily: "'Bricolage Grotesque', sans-serif",
  fontWeight: 600,
  fontSize: 20,
  color: "var(--text-primary)",
}}
```

**Labels:**
```tsx
// Uppercase label (KPI labels, table headers)
style={{
  fontFamily: "'Onest', sans-serif",
  fontWeight: 500,
  fontSize: 12,
  textTransform: "uppercase",
  color: "var(--text-secondary)",
  letterSpacing: "0.03em",
}}
```

**Data values:**
```tsx
// Large KPI number
style={{
  fontFamily: "'Martian Mono', monospace",
  fontWeight: 500,
  fontSize: 36,
  lineHeight: 1,
  color: "var(--text-primary)",
}}

// Table data / inline numbers
style={{
  fontFamily: "'Martian Mono', monospace",
  fontWeight: 400,
  fontSize: 14,
  color: "var(--text-primary)",
}}

// Secondary data (timestamps, small values)
style={{
  fontFamily: "'Martian Mono', monospace",
  fontSize: 12,
  color: "var(--text-secondary)",
}}
```

**Containers:**
```tsx
// Card / panel
style={{
  backgroundColor: "var(--bg-surface-1)",
  border: "1px solid var(--border-default)",
}}
className="rounded-lg"

// Card with accent left border
style={{
  backgroundColor: "var(--bg-surface-1)",
  border: "1px solid var(--border-default)",
  borderLeft: "3px solid var(--accent)",  // or --profit, --warning, --danger, --info
}}

// Input / select background
style={{ backgroundColor: "var(--bg-surface-2)" }}
```

**Layout (Tailwind only):**
- KPI grid: `grid grid-cols-4 gap-4 mb-6` (or `grid-cols-1 md:grid-cols-2` for fewer items)
- Page spacing: `space-y-6` on the root div, or manual `mb-6` between sections
- Flex rows: `flex items-center gap-*` (gap-1 to gap-4 depending on density)
- Content padding: inherited from AppShell (`p-8`)

**Semantic colors — when to use which:**
| Meaning | Color Variable | Dim Variant |
|---|---|---|
| Positive, healthy, profitable | `--profit` | `--profit-dim` |
| Caution, in-progress, paused | `--warning` | `--warning-dim` |
| Critical, failed, negative | `--danger` | `--danger-dim` |
| Informational, neutral-active | `--info` | `--info-dim` |
| Brand accent, selected state | `--accent` | `--accent-dim` |

## Step 5: Register the Page (if new)

If you created a new page, it needs two registrations:

### 5a. Add the route in `App.tsx`:
```tsx
import NewFeaturePage from "@/pages/NewFeaturePage";

// Inside the router children array:
{ path: "/new-feature", element: <NewFeaturePage /> },
```

### 5b. Add a sidebar entry in `Sidebar.tsx`:
```tsx
import { SomeIcon } from "lucide-react";

// Add to the appropriate section (OPERATE, INTELLIGENCE, INFRASTRUCTURE, HISTORY):
{ to: "/new-feature", label: "New Feature", icon: <SomeIcon size={18} /> },
```

Choose the sidebar section based on the feature's domain:
- **OPERATE** — day-to-day operational views (SKUs, cost gate)
- **INTELLIGENCE** — analytics and monitoring (margin, experiments, demand)
- **INFRASTRUCTURE** — system health and configuration (vendors, capital, compliance)
- **HISTORY** — logs and audit trails (kill log)

## Step 6: Verify

After building, confirm everything works and looks right.

1. **Visual check with Playwright** (for visual changes — new pages, layout modifications, new components):
   ```
   Use Playwright MCP tools:
   - browser_navigate to the new/modified page
   - browser_take_screenshot to verify visual output
   - browser_console_messages to confirm no errors/warnings
   ```
   Skip Playwright for non-visual changes (adding hooks, updating types, fixing logic). The TypeScript and lint checks below are sufficient for those.

2. **TypeScript check:**
   ```bash
   cd frontend && npx tsc --noEmit
   ```

3. **Lint check:**
   ```bash
   cd frontend && npm run lint
   ```

4. **Cross-page check:** If you modified a shared component, navigate to other pages that use it and take screenshots to confirm no regressions.

## Constraints

These are non-negotiable:

- **Never modify `components/ui/*` files.** They're shadcn-generated. Use `npx shadcn add <component>` to add new ones.
- **StatusBadge is a shared growth point.** When introducing new domain entities with status fields (e.g., orders with PENDING/SHIPPED/DELIVERED), add their status-to-color mappings to `StatusBadge.tsx`'s `getStatusColor` switch. This is expected — the component grows as the domain grows.
- **Never hardcode colors or fonts.** Always use CSS variables from `index.css`. **Exception:** Recharts SVG elements (`stopColor` in gradients, `stroke` on chart lines) cannot resolve CSS variables. Use the hex values from `index.css` directly (e.g., `#34d399` for `--profit`, `#e5a00d` for `--accent`). This is a known browser limitation, not a style violation.
- **Never add npm dependencies without asking.** The existing stack (React Query, Recharts, Lucide, shadcn) covers most needs.
- **Every page needs three states:** loading (Skeleton), empty (SpriteScene), populated (content). No exceptions.
- **API types must match backend contracts.** When in doubt, check the actual Kotlin DTOs.
- **Use formatters.** Money, percentages, and dates always go through `lib/formatters.ts`. Never format inline.
