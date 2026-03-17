# Commerce Engine Design System Reference

Complete reference for the visual design system. This is the single source of truth for all visual decisions.

## Table of Contents

1. [Identity](#identity)
2. [Color Tokens](#color-tokens)
3. [Typography](#typography)
4. [Spacing and Layout](#spacing-and-layout)
5. [Component Patterns](#component-patterns)
6. [Page Anatomy](#page-anatomy)
7. [File Placement Rules](#file-placement-rules)

## Identity

The Commerce Engine dashboard uses an "Engine Room" aesthetic — dark, industrial, warm. Think control room for an autonomous system, not a consumer SaaS app.

- **Theme:** Dark only (no light mode)
- **Accent:** Warm amber/gold (`#e5a00d`) — the signature color
- **Feel:** Dense but readable, data-forward, slightly playful via pixel-art sprites

## Color Tokens

All colors are CSS variables defined in `frontend/src/index.css`. Never use raw hex values in components.

**SVG exception:** Recharts SVG elements (`stopColor` in `<linearGradient>`, `stroke` on `<Line>`/`<Area>`) cannot resolve CSS `var()` references. Use the hex values directly from the token table below. This is a browser limitation, not a style violation.

### Backgrounds (layered depth)
```
--bg-root:      #0c0c0e   (page background, deepest layer)
--bg-surface-1: #141416   (cards, sidebar, panels)
--bg-surface-2: #1c1c20   (inputs, secondary surfaces)
--bg-surface-3: #24242a   (hover states, tertiary surfaces)
```

### Borders
```
--border-default: #2a2a32  (standard borders)
--border-bright:  #3a3a44  (emphasized borders, focused inputs)
```

### Text
```
--text-primary:   #ececf0  (main text, headings)
--text-secondary: #8888a0  (labels, metadata, supporting text)
--text-tertiary:  #555566  (disabled text, placeholders, section titles in sidebar)
```

### Accent
```
--accent:      #e5a00d                    (primary accent — active nav, highlights)
--accent-hover: #cca30f                   (accent hover state)
--accent-dim:  rgba(229, 160, 13, 0.12)   (accent background tint)
--accent-glow: 0 0 20px rgba(229, 160, 13, 0.15)  (subtle glow effect)
```

### Semantic Colors (paired: solid + dim background)
```
--profit:      #34d399   --profit-dim:  rgba(52, 211, 153, 0.12)    (good/healthy)
--warning:     #fbbf24   --warning-dim: rgba(251, 191, 36, 0.12)    (caution/paused)
--danger:      #f87171   --danger-dim:  rgba(248, 113, 113, 0.12)   (critical/failed)
--info:        #60a5fa   --info-dim:    rgba(96, 165, 250, 0.12)    (informational)
```

### When to Use Which Semantic Color

| Scenario | Color |
|---|---|
| SKU is Listed, Scaled, vendor ACTIVE, compliance CLEARED | `--profit` |
| SKU is in Ideation or ValidationPending | `--info` |
| SKU is in CostGating, StressTesting, or Paused | `--warning` |
| SKU is Terminated, check FAILED, health CRITICAL | `--danger` |
| Selected nav item, brand emphasis | `--accent` |
| Margins above floor (30%) | `--profit` |
| Margins below floor | `--danger` |
| Rates above threshold (refund >5%, chargeback >2%) | `--danger` |
| Rates below threshold | `--profit` |

## Typography

Three fonts loaded via Google Fonts in `index.css`:

### Bricolage Grotesque — Display
```
fontFamily: "'Bricolage Grotesque', sans-serif"
```
Used for: page titles, section headings, card titles, the "COMMERCE ENGINE" sidebar brand.

| Context | Weight | Size |
|---|---|---|
| Page title (h1) | 700 | 28px |
| Section/card title | 600 | 20px |
| Sidebar brand | 700 | 18px (text-lg) |

### Onest — Body
```
fontFamily: "'Onest', sans-serif"
```
Used for: body text, labels, nav items, buttons, metadata, everything that isn't a heading or a number. This is the default body font set on `<body>`.

| Context | Weight | Size | Extras |
|---|---|---|---|
| Body text | 400 | 14px | |
| Nav item | 500 | 14px | |
| Label (uppercase) | 500 | 12px | `textTransform: "uppercase"`, `letterSpacing: "0.03em"` |
| Sidebar section title | 600 | 11px | `textTransform: "uppercase"`, `letterSpacing: "0.05em"` |
| Error/info message | 400 | 14px | |

### Martian Mono — Data
```
fontFamily: "'Martian Mono', monospace"
```
Used for: any numeric value, money amounts, percentages, dates, data points. Makes numbers scannable and aligned.

| Context | Weight | Size |
|---|---|---|
| KPI big number | 500 | 36px |
| Rate/metric display | 500 | 28px |
| Table data (numeric) | 400 | 14px |
| Timestamp / date | 400 | 12px |
| Trend indicator | 400 | 12px |

## Spacing and Layout

### Page-Level
- Pages are wrapped in `AppShell` which provides `p-8` padding
- Root page div usually uses `space-y-6` or manual `mb-6` between sections
- Page heading has `marginBottom: 24` (24px)

### Grid Patterns
```
KPI cards (4 items):     grid grid-cols-4 gap-4 mb-6
KPI cards (2 items):     grid grid-cols-1 md:grid-cols-2 gap-4
Detail sections:         grid grid-cols-1 md:grid-cols-2 gap-4
```

### Card Padding
- KPI cards: `p-4` (16px all sides)
- Content cards: Use `<Card>` + `<CardContent>` (has its own padding)
- Custom panels: `p-4` to `p-6`

### Element Spacing
- Between label and value: `mb-1` or `marginBottom: 4`
- Between icon and text: `gap-1.5` to `gap-3`
- Between filter controls: `gap-4`
- Between action buttons: `gap-1`

## Component Patterns

### KPI Card
```tsx
<KpiCard
  label="Active SKUs"     // uppercase label text
  value={42}              // big number (or formatted string)
  accentColor="info"      // profit | warning | danger | info | accent
  trend={{                // optional trend indicator
    value: 5,
    direction: "up",      // "up" (green) or "down" (red)
  }}
/>
```

### DataTable
```tsx
const columns: Column<MyType>[] = [
  { key: "name", header: "Name" },
  { key: "status", header: "Status",
    render: (value) => <StatusBadge status={value} /> },
  { key: "createdAt", header: "Created",
    render: (value) => <span style={{
      fontFamily: "'Martian Mono', monospace",
      fontSize: 12,
      color: "var(--text-secondary)",
    }}>{formatDate(value)}</span> },
];

<DataTable
  columns={columns}
  data={items}
  onRowClick={(row) => navigate(`/items/${row.id}`)}
/>
```

The DataTable automatically uses Martian Mono for numeric-looking values.

### StatusBadge
```tsx
<StatusBadge status="Listed" />       // green
<StatusBadge status="Paused" />       // yellow
<StatusBadge status="Terminated" />   // red
<StatusBadge status="Ideation" size="sm" />  // smaller variant
```

### Card Container
```tsx
<Card style={{
  backgroundColor: "var(--bg-surface-1)",
  border: "1px solid var(--border-default)",
}}>
  <CardHeader>
    <CardTitle style={{
      fontFamily: "'Bricolage Grotesque', sans-serif",
      fontWeight: 600,
      fontSize: 20,
      color: "var(--text-primary)",
    }}>
      Section Title
    </CardTitle>
  </CardHeader>
  <CardContent>
    {/* content */}
  </CardContent>
</Card>
```

### Loading Skeleton
```tsx
// Match the shape of what will render
<Skeleton className="h-24 rounded-lg" />      // KPI card placeholder
<Skeleton className="h-8 w-48 rounded-lg" />  // text/button placeholder
<Skeleton className="h-[300px] w-full rounded-lg" />  // chart placeholder
```

### Empty State
```tsx
<div className="flex flex-col items-center justify-center py-20">
  <SpriteScene
    message="No data yet. Something descriptive and friendly!"
    animation="idle"
  >
    <Shipper size={48} />  {/* or Scout, Analyst, Guard, Reaper */}
  </SpriteScene>
</div>
```

Choose the sprite based on context:
- `Shipper` — general operations, SKUs, orders
- `Scout` — discovery, demand, experiments
- `Analyst` — margins, capital, pricing
- `Guard` — compliance, risk
- `Reaper` — kill log, terminations

### Select / Dropdown
```tsx
<Select value={value} onValueChange={setValue}>
  <SelectTrigger style={{
    backgroundColor: "var(--bg-surface-2)",
    borderColor: "var(--border-bright)",
    color: "var(--text-primary)",
    fontFamily: "'Onest', sans-serif",
    fontSize: 14,
    minWidth: 180,
  }}>
    <SelectValue placeholder="Select..." />
  </SelectTrigger>
  <SelectContent style={{
    backgroundColor: "var(--bg-surface-2)",
    borderColor: "var(--border-bright)",
  }}>
    <SelectItem value="opt1">Option 1</SelectItem>
  </SelectContent>
</Select>
```

## Page Anatomy

Every page follows this structure:

```
┌─────────────────────────────────────────────┐
│  Page Title (Bricolage Grotesque, 28px, 700)│
│  [optional: filter controls on the right]   │
├─────────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐       │
│  │ KPI  │ │ KPI  │ │ KPI  │ │ KPI  │       │
│  │ Card │ │ Card │ │ Card │ │ Card │       │
│  └──────┘ └──────┘ └──────┘ └──────┘       │
├─────────────────────────────────────────────┤
│  Main Content Area                          │
│  (DataTable, charts, detail cards, etc.)    │
│                                             │
│                                             │
└─────────────────────────────────────────────┘
```

Three mandatory states:
1. **Loading** — Skeleton placeholders matching the populated layout shape
2. **Empty** — SpriteScene with a friendly, context-appropriate message
3. **Populated** — The actual content with data

## File Placement Rules

| What | Where |
|---|---|
| New page component | `frontend/src/pages/NewFeaturePage.tsx` |
| New reusable component | `frontend/src/components/NewComponent.tsx` |
| New API hooks | `frontend/src/api/{module}.ts` |
| New/updated types | `frontend/src/api/types.ts` |
| New formatter | `frontend/src/lib/formatters.ts` |
| Route registration | `frontend/src/App.tsx` (router children array) |
| Sidebar entry | `frontend/src/components/layout/Sidebar.tsx` (sections array) |
| New shadcn component | Run `npx shadcn add <component>` — never create manually |
| Design token changes | `frontend/src/index.css` (`:root` block) — rare, needs justification |

**Naming conventions:**
- Pages: `PascalCase` + `Page` suffix → `OrderListPage.tsx`
- Components: `PascalCase` → `OrderStatusCard.tsx`
- Hooks: `use` prefix + `PascalCase` → `useOrders()`
- Types: `PascalCase` + `Response`/`Request` suffix → `OrderResponse`
