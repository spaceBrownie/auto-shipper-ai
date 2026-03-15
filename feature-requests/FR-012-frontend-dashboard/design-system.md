# FR-012: Design System — "The Engine Room"

## Concept

The operator is watching an autonomous machine run. This dashboard is the engine room —
dark, precise, alive. Every color communicates system state. Every number is immediately
readable. The interface is dense with data but never chaotic — it's a well-organized
instrument panel, not a consumer app.

**Differentiator:** A warm amber accent (like indicator lights in a machine shop) cuts
through the dark interface. This is NOT the cold blue/purple corporate dashboard or the
generic "AI product" look. It's industrial, warm, and purposeful.

---

## Typography

Three typefaces, each with a clear role:

| Role | Font | Weight | Usage |
|---|---|---|---|
| Display / Headings | **Bricolage Grotesque** | 600–800 | Page titles, KPI values, section headers |
| Body / UI | **Onest** | 400–500 | Navigation, labels, descriptions, table text |
| Data / Mono | **Martian Mono** | 400 | All numbers, percentages, currency, UUIDs, dates |

```css
@import url('https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@400;600;700;800&family=Onest:wght@400;500;600&family=Martian+Mono:wght@400;500&display=swap');
```

### Type Scale

| Token | Size | Line Height | Font | Usage |
|---|---|---|---|---|
| `--text-page-title` | 28px | 1.2 | Bricolage 700 | Page headings |
| `--text-section-title` | 20px | 1.3 | Bricolage 600 | Card/section headings |
| `--text-kpi-value` | 36px | 1.0 | Martian Mono 500 | KPI card large numbers |
| `--text-table-header` | 12px | 1.4 | Onest 600 uppercase | Table column headers |
| `--text-body` | 14px | 1.5 | Onest 400 | Body text, descriptions |
| `--text-label` | 12px | 1.4 | Onest 500 | Labels, captions |
| `--text-data` | 14px | 1.4 | Martian Mono 400 | Table numbers, data cells |
| `--text-small-data` | 12px | 1.4 | Martian Mono 400 | Secondary data, timestamps |

---

## Color System

### Base Palette (warm dark)

```css
:root {
  /* Backgrounds — warm near-black, not pure black */
  --bg-root: #0c0c0e;
  --bg-surface-1: #141416;      /* sidebar, cards */
  --bg-surface-2: #1c1c20;      /* elevated cards, popovers, dropdowns */
  --bg-surface-3: #24242a;      /* hover states, active rows */

  /* Borders */
  --border-default: #2a2a32;
  --border-bright: #3a3a44;     /* emphasized, focused inputs */

  /* Text */
  --text-primary: #ececf0;
  --text-secondary: #8888a0;
  --text-tertiary: #555566;     /* disabled, placeholder */

  /* Accent — warm amber/gold (the signature) */
  --accent: #e5a00d;
  --accent-hover: #cca30f;
  --accent-dim: rgba(229, 160, 13, 0.12);
  --accent-glow: 0 0 20px rgba(229, 160, 13, 0.15);

  /* Semantic */
  --profit: #34d399;            /* emerald-400 — healthy, passed, active, cleared */
  --profit-dim: rgba(52, 211, 153, 0.12);
  --warning: #fbbf24;           /* amber-400 — caution, paused, gating */
  --warning-dim: rgba(251, 191, 36, 0.12);
  --danger: #f87171;            /* red-400 — kill, terminate, failed, critical */
  --danger-dim: rgba(248, 113, 113, 0.12);
  --info: #60a5fa;              /* blue-400 — links, pending, informational */
  --info-dim: rgba(96, 165, 250, 0.12);
}
```

### shadcn/ui CSS Variable Mapping

```css
:root {
  --background: 0 0% 5%;           /* #0c0c0e */
  --foreground: 240 5% 93%;        /* #ececf0 */
  --card: 240 4% 8%;               /* #141416 */
  --card-foreground: 240 5% 93%;
  --popover: 240 5% 12%;           /* #1c1c20 */
  --popover-foreground: 240 5% 93%;
  --primary: 41 90% 48%;           /* #e5a00d — amber accent */
  --primary-foreground: 0 0% 5%;
  --secondary: 240 4% 14%;         /* #24242a */
  --secondary-foreground: 240 5% 93%;
  --muted: 240 4% 14%;
  --muted-foreground: 245 10% 58%; /* #8888a0 */
  --accent: 240 4% 14%;
  --accent-foreground: 240 5% 93%;
  --destructive: 0 72% 68%;        /* #f87171 */
  --destructive-foreground: 0 0% 5%;
  --border: 245 8% 18%;            /* #2a2a32 */
  --input: 245 8% 18%;
  --ring: 41 90% 48%;              /* amber ring on focus */
  --radius: 0.5rem;
}
```

### Semantic Color Map — StatusBadge

| State | Background | Text | Context |
|---|---|---|---|
| `Ideation` | `--info-dim` | `--info` | Early SKU stage |
| `ValidationPending` | `--info-dim` | `--info` | Awaiting validation |
| `CostGating` | `--warning-dim` | `--warning` | Gate check in progress |
| `StressTesting` | `--warning-dim` | `--warning` | Gate check in progress |
| `Listed` | `--profit-dim` | `--profit` | Active and healthy |
| `Scaled` | `--profit-dim` + border `--accent` | `--profit` | Thriving, priority |
| `Paused` | `--warning-dim` | `--warning` | Needs attention |
| `Terminated` | `--danger-dim` | `--danger` | Dead |
| `ACTIVE` | `--profit-dim` | `--profit` | Running experiment |
| `VALIDATED` | `--profit-dim` | `--profit` | Confirmed |
| `FAILED` | `--danger-dim` | `--danger` | Dead experiment |
| `CLEARED` | `--profit-dim` | `--profit` | Compliance passed |
| `HEALTHY` | `--profit-dim` | `--profit` | Reserve health |
| `CRITICAL` | `--danger-dim` | `--danger` | Reserve danger |

---

## Layout

### App Shell

```
┌─────────────────────────────────────────────────────────────────┐
│ ┌──────────┐ ┌────────────────────────────────────────────────┐ │
│ │          │ │ [Engine Pulse Bar — thin animated gradient]     │ │
│ │ COMMERCE │ ├────────────────────────────────────────────────┤ │
│ │ ENGINE   │ │                                                │ │
│ │ ●        │ │  Page Title                                    │ │
│ │          │ │                                                │ │
│ │ ──────── │ │  ┌─────────┐ ┌─────────┐ ┌─────────┐         │ │
│ │ OPERATE  │ │  │ KPI     │ │ KPI     │ │ KPI     │         │ │
│ │ ▸ SKUs   │ │  │ Card    │ │ Card    │ │ Card    │         │ │
│ │   Cost   │ │  └─────────┘ └─────────┘ └─────────┘         │ │
│ │   Gate   │ │                                                │ │
│ │ ──────── │ │  ┌────────────────────────────────────────┐   │ │
│ │ INTEL    │ │  │                                        │   │ │
│ │   Margin │ │  │  Data Table / Chart                    │   │ │
│ │   Expts  │ │  │                                        │   │ │
│ │   Demand │ │  │                                        │   │ │
│ │ ──────── │ │  └────────────────────────────────────────┘   │ │
│ │ INFRA    │ │                                                │ │
│ │   Vendor │ │                                                │ │
│ │   Capital│ │                                                │ │
│ │   Comply │ │                                                │ │
│ │ ──────── │ │                                                │ │
│ │ HISTORY  │ │                                                │ │
│ │   Kill   │ │                                                │ │
│ │   Log    │ │                                                │ │
│ └──────────┘ └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

- **Sidebar**: Fixed, 256px wide, `--bg-surface-1`
- **Main content**: Scrollable, `--bg-root`, padding 32px
- **Engine Pulse Bar**: 3px tall bar at top of main content area — slow animated gradient (amber → transparent → amber) cycling every 8s. Indicates the system is alive and connected. Goes red if API connection lost.
- **No top bar**: No auth, no user menu. The pulse bar is the only top element.

### Sidebar Navigation

Grouped with thin separators and uppercase section labels:

```
COMMERCE ENGINE                    ← Bricolage 700, 18px, --accent color
● System Online                    ← tiny pulse dot (green = connected)

─────────────────
OPERATE                            ← Onest 600, 11px, uppercase, --text-tertiary
  ◈ SKU Portfolio                  ← Onest 500, 14px
  ◇ Cost Gate Runner

INTELLIGENCE
  ◇ Margin Monitor
  ◇ Experiments
  ◇ Demand Signals

INFRASTRUCTURE
  ◇ Vendors
  ◇ Capital
  ◇ Compliance

HISTORY
  ◇ Kill Log
```

- Active item: left 3px amber border, amber text, `--accent-dim` background
- Hover: `--bg-surface-3` background
- Icons: Lucide icons, 18px, same color as text

### Lucide Icon Assignments

| View | Icon | Rationale |
|---|---|---|
| SKU Portfolio | `Package` | Products/inventory |
| Cost Gate Runner | `ShieldCheck` | Verification gate |
| Margin Monitor | `TrendingUp` | Margin trends |
| Experiments | `FlaskConical` | Testing/hypotheses |
| Demand Signals | `Radio` | Signal detection |
| Vendors | `Truck` | Supply chain |
| Capital | `Vault` | Reserve/treasury |
| Compliance | `Scale` | Legal/regulatory |
| Kill Log | `Skull` | Terminated items |

---

## Component Patterns

### KPI Card

```
┌─────────────────────────────────┐
│ ┃  Active SKUs            ▲ 2  │   ← 3px left border (semantic color)
│ ┃  12                          │   ← Martian Mono 500, 36px
│ ┃  from 10 last period         │   ← Onest 400, 12px, --text-secondary
└─────────────────────────────────┘
```

- Background: `--bg-surface-1`
- Border: `--border-default`
- Left accent border: 3px, color varies by meaning (profit/warning/danger/info)
- Value: Martian Mono, 36px, `--text-primary`
- Label: Onest 500, 12px, uppercase, `--text-secondary`
- Trend: small arrow + delta value, colored profit/danger

### Data Table

```
┌──────────────────────────────────────────────────────────────┐
│  NAME ▾        CATEGORY    STATE         MARGIN    CREATED   │  ← header row
├──────────────────────────────────────────────────────────────┤
│  Bamboo Mat    Home        ● Listed      54.2%     Mar 12    │  ← data row
│  Cork Coaster  Kitchen     ● Paused      31.8%     Mar 10    │
│  Hemp Bag      Accessories ◉ Terminated  —         Mar 08    │  ← hover = surface-3
└──────────────────────────────────────────────────────────────┘
```

- Header: Onest 600, 12px, uppercase, `--text-tertiary`, no background
- Rows: Onest 400 for text, Martian Mono 400 for numbers
- Row hover: `--bg-surface-3`
- No alternating row colors — too noisy for dark theme
- Sortable columns: subtle caret indicator
- Status column uses inline StatusBadge
- Row click: navigates to detail page (cursor pointer, subtle highlight)

### StatusBadge

Pill shape, 24px height, 6px horizontal padding:
```
 ● Listed      → green bg (dim), green text, tiny green dot
 ● Paused      → amber bg (dim), amber text
 ◉ Terminated  → red bg (dim), red text
```

- Border-radius: 9999px (full pill)
- Font: Onest 500, 12px
- Leading dot: 6px circle, same color as text
- Background: semantic dim color
- No border

### Charts (Recharts Customization)

```
  50% ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   ← dashed reference line, --profit at 30% opacity
       ╱‾‾‾╲    ╱‾‾‾╲
  ── ╱       ‾‾╱      ╲──      ← gross margin line, --profit solid
  30% ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   ← dashed reference line, --danger at 30% opacity
      ╱‾‾╲   ╱‾╲
  ──╱      ‾╱    ╲─────        ← net margin line, --accent solid

  Mar 1  Mar 15  Mar 30  Apr 14  Apr 30
```

- Background: transparent (card provides background)
- Grid lines: none (too noisy in dark theme)
- Axis labels: Martian Mono 400, 11px, `--text-tertiary`
- Lines: 2px stroke, rounded caps
- Gross margin: `--profit` (#34d399)
- Net margin: `--accent` (#e5a00d)
- Reference lines: dashed, 1px, semantic color at 30% opacity
- Tooltip: `--bg-surface-2` background, `--border-bright` border, no shadow
- Area fill: subtle gradient from line color at 8% opacity to transparent
- Dot on hover: 6px circle at line color

### Reserve Gauge

```
  Reserve Health
  ┌────────────────────────────────────────────────────────┐
  │ ████████████████████████████░░░░░░░░░░░░░░░░░░░░░░░░░ │
  │ ▲ $12,450                                     Target  │
  └────────────────────────────────────────────────────────┘
    CRITICAL ◄─────────►  HEALTHY ◄──────────────────────►
    0%                10%                               100%
```

- Bar: 12px height, border-radius 6px
- Fill: gradient from `--danger` (0-10%) to `--warning` (10-15%) to `--profit` (15%+)
- Marker: small triangle below current position
- Background: `--bg-surface-3`
- Labels: Martian Mono for values, Onest for labels

### Cost Breakdown Table (13 components)

```
┌───────────────────────────────────────────┐
│  Cost Component          Amount    Share  │
├───────────────────────────────────────────┤
│  Supplier Unit Cost      $12.50   28.4%  │  ← bar chart inline
│  Inbound Shipping         $3.20    7.3%  │
│  Outbound Shipping        $4.80   10.9%  │
│  Platform Fee             $2.10    4.8%  │
│  Processing Fee           $1.40    3.2%  │
│  Packaging                $0.80    1.8%  │
│  Return Handling          $1.20    2.7%  │
│  CAC                      $6.50   14.8%  │
│  Warehousing              $1.00    2.3%  │
│  Customer Service         $0.50    1.1%  │
│  Refund Allowance         $2.20    5.0%  │
│  Chargeback Allowance     $0.88    2.0%  │
│  Taxes & Duties           $3.50    8.0%  │
├───────────────────────────────────────────┤
│  FULLY BURDENED          $43.98  100.0%  │  ← bold, --accent color
└───────────────────────────────────────────┘
```

- Each row has a tiny inline horizontal bar (8px tall) showing relative share
- Bar color: `--accent` at 40% opacity
- Total row: Bricolage 600, `--accent` color, top border `--border-bright`

### Stress Test Result Card

```
┌─────────────────────────────────────────────────────────────┐
│  ✓ STRESS TEST PASSED                                       │  ← or ✗ FAILED
│                                                             │
│  Gross Margin    Net Margin     Stressed Cost    Est. Price │
│  62.4%           38.1%          $27.15           $43.98     │
│                                                             │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────┐│
│  │2x Ship  │ │+15% CAC │ │+10% Sup │ │5% Refund│ │2% CB ││
│  │ $6.40   │ │ $7.48   │ │ $13.75  │ │ $2.20   │ │$0.88 ││
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └──────┘│
└─────────────────────────────────────────────────────────────┘
```

- PASSED: header bar `--profit`, icon checkmark
- FAILED: header bar `--danger`, icon X
- Stress scenario cards: mini cards inside, `--bg-surface-2`

### Cost Gate Runner — Multi-Step Form

```
Step 1              Step 2              Step 3              Step 4
● Select SKU ─────── ○ Verify Costs ───── ○ Stress Test ───── ○ Result
  (active)           (pending)            (pending)           (pending)
```

- Step indicator: horizontal, dots connected by lines
- Active step: amber dot, amber text
- Completed step: profit green dot, green checkmark
- Pending: `--text-tertiary` dot and line
- Each step is a card (`--bg-surface-1`) with form fields
- Input focus: `--accent` border glow (box-shadow: `--accent-glow`)
- Submit button: amber background, dark text, Onest 600

---

## Interaction & Motion

### Principles
- **Purposeful, not decorative** — animation communicates state, not personality
- **CSS-first** — prefer CSS transitions over JS animation libraries
- **Fast** — 150ms for hovers, 200ms for state changes, 300ms for page transitions

### Specific Animations

| Element | Trigger | Animation | Duration |
|---|---|---|---|
| Engine Pulse Bar | Continuous | Amber gradient slides left-to-right | 8s linear infinite |
| Engine Pulse Bar (error) | API disconnect | Switches to red, faster pulse | 2s |
| System status dot | Continuous | Subtle scale pulse (1.0 → 1.3 → 1.0) | 2s ease infinite |
| KPI Card value | Data update | Flash amber background, fade to normal | 400ms |
| Table row | Hover | Background fade to `--bg-surface-3` | 150ms |
| StatusBadge | Mount | Fade in + slight scale up | 200ms |
| Page content | Route change | Fade in from opacity 0 → 1 | 200ms |
| Sidebar active item | Click | Left border slides in from bottom | 150ms |
| Chart tooltip | Hover | Fade in | 100ms |
| Form step transition | Step complete | Current card slides left, next slides in | 300ms |
| Reserve gauge fill | Mount / update | Width animates from 0 to value | 600ms ease-out |
| Stress test result | Mount | Card slides up + header bar color fills | 400ms |

### Engine Pulse Bar CSS

```css
.engine-pulse {
  height: 3px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    var(--accent) 50%,
    transparent 100%
  );
  background-size: 200% 100%;
  animation: pulse-slide 8s linear infinite;
}

.engine-pulse--error {
  --accent: var(--danger);
  animation-duration: 2s;
}

@keyframes pulse-slide {
  0% { background-position: -100% 0; }
  100% { background-position: 200% 0; }
}
```

---

## Page-by-Page Layout

### 1. SKU Portfolio Page

```
┌─────────────────────────────────────────────────────────────────┐
│  SKU Portfolio                              [State Filter ▾]    │
│                                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │ Active   │ │ Listed   │ │ Paused   │ │ Terminatd│          │
│  │ 12       │ │ 8        │ │ 2        │ │ 5        │          │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ NAME ▾       CATEGORY    STATE      MARGIN    ACTIONS       ││
│  │ Bamboo Mat   Home        ● Listed   54.2%     [⏸] [✕]     ││
│  │ Cork Coaster Kitchen     ● Paused   31.8%     [▶] [✕]     ││
│  │ Hemp Bag     Accessories ◉ Termin.  —         —            ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 2. SKU Detail Page (Tabbed)

```
┌─────────────────────────────────────────────────────────────────┐
│  ← Back    Bamboo Mat                       ● Listed           │
│                                                                 │
│  [Overview] [Cost] [Stress Test] [Pricing] [P&L] [Compliance]  │
│  ─────────                                                      │
│                                                                 │
│  Overview tab:                                                  │
│  ┌──────────────────┐  ┌──────────────────────────────────────┐│
│  │ Name: Bamboo Mat │  │ State History Timeline               ││
│  │ Category: Home   │  │ Ideation → CostGating → StressTesting││
│  │ State: Listed    │  │ → Listed                             ││
│  │ Created: Mar 12  │  │ (vertical timeline with dates)       ││
│  └──────────────────┘  └──────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 3. Cost Gate Runner Page

```
┌─────────────────────────────────────────────────────────────────┐
│  Cost Gate Runner                                               │
│                                                                 │
│  ● Select SKU ──── ○ Verify Costs ──── ○ Stress Test ──── ○ Result│
│                                                                 │
│  Step 1: Select SKU                                             │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ Select existing SKU in CostGating state:                    ││
│  │ [Dropdown: SKU selector ▾]                                  ││
│  │                                                             ││
│  │ — or —                                                      ││
│  │                                                             ││
│  │ Create new SKU:                                             ││
│  │ Name: [____________]  Category: [____________]              ││
│  │                                                             ││
│  │                                        [Next Step →]        ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 4. Margin Monitor Page

```
┌─────────────────────────────────────────────────────────────────┐
│  Margin Monitor                                                 │
│                                                                 │
│  ┌────────────────────┐  ┌────────────────────┐                │
│  │ Portfolio Blended  │  │ SKUs Above Floor   │                │
│  │ 42.8%              │  │ 10 / 12            │                │
│  └────────────────────┘  └────────────────────┘                │
│                                                                 │
│  SKU: [Bamboo Mat ▾]    Period: [90 days ▾]                    │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                                                             ││
│  │  50% ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ gross floor          ││
│  │       ╱‾‾‾╲    ╱‾‾‾╲                                       ││
│  │  ── ╱       ‾‾╱      ╲── gross margin (green)              ││
│  │  30% ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ net floor            ││
│  │      ╱‾‾╲   ╱‾╲                                            ││
│  │  ──╱      ‾╱    ╲───── net margin (amber)                  ││
│  │                                                             ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  Refund Rate: 2.1%    Chargeback Rate: 0.8%                   │
└─────────────────────────────────────────────────────────────────┘
```

### 5. Capital Overview Page

```
┌─────────────────────────────────────────────────────────────────┐
│  Capital Overview                                               │
│                                                                 │
│  Reserve Health                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ ████████████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ ││
│  │  HEALTHY   $12,450 / $52,000 target                        ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  Priority Ranking (by risk-adjusted return)                     │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ #  SKU            NET MARGIN  REVENUE   RISK   RETURN      ││
│  │ 1  Bamboo Mat     54.2%       $4,200    0.12   $3,696      ││
│  │ 2  Cork Coaster   42.1%       $2,800    0.18   $2,296      ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  ⚠ Refund Alert: 3 SKUs above 3% refund rate in last 7 days   │
└─────────────────────────────────────────────────────────────────┘
```

### 6. Kill Log Page

```
┌─────────────────────────────────────────────────────────────────┐
│  Kill Log                                                       │
│                                                                 │
│  ┌────────────────────┐ ┌────────────────────┐                 │
│  │ Total Terminated   │ │ By Reason          │                 │
│  │ 5                  │ │ Margin: 2          │                 │
│  └────────────────────┘ │ Stress: 1          │                 │
│                         │ Refund: 1          │                 │
│                         │ Compliance: 1      │                 │
│                         └────────────────────┘                 │
│                                                                 │
│  Terminated SKUs                                                │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ NAME          REASON                  TERMINATED             ││
│  │ Widget X      MARGIN_BELOW_FLOOR      Mar 10, 2026          ││
│  │ Gadget Y      STRESS_TEST_FAILED      Mar 08, 2026          ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  Pending Kill Recommendations                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ SKU           DAYS NEG   AVG MARGIN   DETECTED   [CONFIRM] ││
│  │ Cork Coaster  9          28.1%        Mar 14     [Kill ✕]  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

## Responsive Behavior

Target: 1280px+ screens. The dashboard is optimized for desktop monitors.

| Breakpoint | Behavior |
|---|---|
| ≥ 1440px | Full layout — sidebar 256px, 4-column KPI grid |
| 1280–1439px | Sidebar 240px, 3-column KPI grid |
| < 1280px | Sidebar collapses to icon-only (64px), 2-column KPI grid |

---

## File Additions to Implementation Plan

This design system requires these additions to the project structure:

```
frontend/src/
├── styles/
│   └── globals.css              (CSS variables, font imports, engine pulse, sprite anims)
├── components/
│   ├── layout/
│   │   ├── Sidebar.tsx
│   │   ├── AppShell.tsx
│   │   └── EnginePulse.tsx      (animated status bar)
│   ├── sprites/
│   │   ├── Shipper.tsx          (SVG sprite — hard hat, clipboard)
│   │   ├── Analyst.tsx          (SVG sprite — glasses, chart)
│   │   ├── Guard.tsx            (SVG sprite — shield, stern)
│   │   ├── Scout.tsx            (SVG sprite — binoculars, explorer hat)
│   │   ├── Reaper.tsx           (SVG sprite — tiny scythe, hood)
│   │   └── SpriteScene.tsx      (sprite + speech bubble + animation wrapper)
│   └── StepIndicator.tsx        (Cost Gate Runner multi-step flow)
├── lib/
│   └── konami.ts                (useKonamiCode hook + ship-it easter egg)
```

### shadcn/ui Component Customizations

Override default shadcn theme in `globals.css` with the CSS variables above. Specific overrides:

- **Badge**: Use as StatusBadge base — override colors per state
- **Table**: Remove default borders, use hover rows
- **Card**: Use `--bg-surface-1`, subtle `--border-default`
- **Button**: Primary uses `--accent` bg, dark text. Destructive uses `--danger`
- **Input**: `--bg-surface-2` bg, `--border-default` border, `--accent` focus ring
- **Select/Dropdown**: `--bg-surface-2` bg, `--border-bright` border
- **Tabs**: Underline style (not pill), `--accent` active underline
- **Skeleton**: `--bg-surface-3` animated shimmer

---

## Sprites — "The Crew"

The engine room has a crew. Tiny SVG sprite characters (24–32px) appear throughout the
dashboard, reacting to system state. They give the data-dense interface warmth and
personality without undermining its seriousness. Think Tamagotchi-meets-ops-dashboard.

### Sprite Characters

All sprites are simple SVG illustrations — round heads, minimal features, expressive
through posture and accessories. Rendered as inline SVGs for CSS color theming.

| Sprite | Name | Role | Visual |
|---|---|---|---|
| `sprite-shipper` | Shipper | Manages SKUs and fulfillment | Hard hat, clipboard, amber outfit |
| `sprite-analyst` | Analyst | Watches margins and capital | Glasses, tiny chart in hand, green outfit |
| `sprite-guard` | Guard | Runs compliance and cost gates | Shield, stern expression, blue outfit |
| `sprite-scout` | Scout | Discovers demand signals | Binoculars, explorer hat, teal outfit |
| `sprite-reaper` | Reaper | Handles kills and terminations | Tiny scythe, hood, red outfit (friendly, not scary) |

### Where Sprites Appear

| Context | Sprite | Behavior |
|---|---|---|
| **Empty state — SKU Portfolio** | Shipper sitting on a box, waving | "No SKUs yet. Let's ship something!" |
| **Empty state — Experiments** | Scout looking through binoculars | "No experiments running. Go find some demand!" |
| **Empty state — Kill Log** | Reaper napping against scythe | "Nothing terminated. The engine is thriving." |
| **Empty state — Vendors** | Shipper shrugging | "No vendors registered yet." |
| **Empty state — Demand Signals** | Scout tinkering with a radio | "DemandScanJob isn't wired up yet. Coming soon!" |
| **Stress test PASSED** | Analyst doing a tiny fist pump | Appears briefly next to result, fades after 3s |
| **Stress test FAILED** | Guard shaking head | Appears briefly next to result |
| **Kill confirmed** | Reaper tipping hat | Appears briefly in kill log |
| **Reserve CRITICAL** | Analyst sweating, waving red flag | Persistent in Capital Overview when reserve is critical |
| **Reserve HEALTHY** | Analyst giving thumbs up | Persistent in Capital Overview when healthy |
| **Cost Gate Step 1** | Guard at a gate, arms crossed | Decorative, next to step indicator |
| **Cost Gate Step 4 (pass)** | Guard stepping aside, gate open | Decorative, replaces Step 1 pose |
| **Sidebar footer** | Shipper walking with package | Tiny 20px sprite, CSS walk animation (2-frame) |

### Sprite Implementation

```
frontend/src/
├── components/
│   └── sprites/
│       ├── Shipper.tsx        (SVG component, accepts size + className)
│       ├── Analyst.tsx
│       ├── Guard.tsx
│       ├── Scout.tsx
│       ├── Reaper.tsx
│       └── SpriteScene.tsx    (composable: sprite + speech bubble + animation)
```

- All sprites are React components returning inline `<svg>` — no image files
- Accept `size` prop (default 32px) and `className` for positioning
- `SpriteScene` wraps a sprite with an optional speech bubble (tooltip-style) and CSS animation class
- Sprites use CSS variables for colors so they adapt to the theme
- Animations are CSS-only: `idle` (subtle bob), `celebrate` (bounce), `alert` (shake), `walk` (2-frame toggle)

### Sprite CSS Animations

```css
@keyframes sprite-idle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-2px); }
}

@keyframes sprite-celebrate {
  0% { transform: translateY(0) scale(1); }
  25% { transform: translateY(-6px) scale(1.1); }
  50% { transform: translateY(0) scale(1); }
  75% { transform: translateY(-3px) scale(1.05); }
  100% { transform: translateY(0) scale(1); }
}

@keyframes sprite-alert {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-2px); }
  75% { transform: translateX(2px); }
}

@keyframes sprite-walk {
  0%, 100% { transform: translateX(0) scaleX(1); }
  50% { transform: translateX(4px) scaleX(-1); }
}

.sprite { animation: sprite-idle 3s ease-in-out infinite; }
.sprite--celebrate { animation: sprite-celebrate 600ms ease-out 1; }
.sprite--alert { animation: sprite-alert 300ms ease-in-out 3; }
.sprite--walk { animation: sprite-walk 1.5s steps(2) infinite; }
```

---

## Easter Egg — "The Konami Engine"

### Trigger
Type the Konami code anywhere in the dashboard:
`↑ ↑ ↓ ↓ ← → ← → B A`

### What Happens

1. **The Engine Pulse Bar goes rainbow** — cycles through all semantic colors (profit → accent → info → warning → danger) in a smooth gradient loop for 10 seconds
2. **All 5 sprites appear in the sidebar footer** — walking in a tiny parade, single file, looping
3. **A toast appears**: "You found the crew! All systems nominal." with a party popper icon
4. **The page title briefly changes** to "COMMERCE ENGINE v1.0 — CREW MODE ACTIVATED"
5. After 10 seconds, everything returns to normal

### Implementation

```typescript
// src/lib/konami.ts
const KONAMI = ['ArrowUp','ArrowUp','ArrowDown','ArrowDown',
                'ArrowLeft','ArrowRight','ArrowLeft','ArrowRight',
                'KeyB','KeyA'];

export function useKonamiCode(onActivate: () => void) {
  const [index, setIndex] = useState(0);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.code === KONAMI[index]) {
        const next = index + 1;
        if (next === KONAMI.length) {
          onActivate();
          setIndex(0);
        } else {
          setIndex(next);
        }
      } else {
        setIndex(0);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [index, onActivate]);
}
```

### Secret #2 — "Ship It"

Click the Shipper sprite in the sidebar footer 5 times rapidly. The sprite jumps on a
skateboard and rides across the bottom of the screen, leaving a trail of tiny packages.
A toast appears: "Ship it! No questions asked." Lasts 5 seconds.

---

## Implementation Notes

1. **Font loading**: Use `font-display: swap` to avoid FOIT. Load via Google Fonts `<link>` in `index.html`.
2. **Dark-only**: No light mode toggle needed. Single operator, single preference.
3. **Chart theming**: Create a `CHART_THEME` constant with Recharts color/style overrides used across all chart components.
4. **Formatters**: `formatMoney("12450.00", "USD")` → `"$12,450.00"`, `formatPercent(54.2)` → `"54.2%"`, `formatDate("2026-03-12T...")` → `"Mar 12, 2026"`. All use Martian Mono when rendered.
5. **Empty states**: Each page has a distinct empty state with a crew sprite + message (see Sprites section). Sprite at 48px, message in Onest 400, `--text-tertiary`. Sprite uses `idle` animation.
6. **Easter eggs**: `useKonamiCode` hook registered in `App.tsx`. Sidebar Shipper sprite tracks click count for "Ship It" easter egg. Both are client-side only, no API calls.
