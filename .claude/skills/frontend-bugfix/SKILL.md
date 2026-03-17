---
name: frontend-bugfix
description: >
  Systematic diagnosis and fix protocol for frontend bugs in this React + Vite + shadcn/ui dashboard.
  Use this skill when the user reports a frontend issue: console errors, blank pages, broken layouts,
  data not rendering, styling problems, React warnings, or anything visually wrong in the dashboard.
  Also use when the user says "fix the frontend", "there's a console error", "the page is broken",
  "something looks wrong on the dashboard", "this component isn't working", or pastes a browser
  error/warning. Even vague reports like "the page looks weird" or "something's off" should trigger
  this skill — it provides the diagnostic structure that prevents guesswork.
---

# Frontend Bug Fix

A systematic protocol for diagnosing and fixing frontend bugs in the Commerce Engine dashboard. This skill exists because the operator is a backend engineer — it provides the frontend diagnostic structure that prevents the "change random things and hope it works" anti-pattern.

## The Core Idea

Frontend bugs feel chaotic because there are multiple layers where things can go wrong (network, state, render, style), and symptoms rarely point directly at the cause. This protocol classifies the bug first, then follows the right trace path for that class. Think of it like a backend engineer's approach to a production incident: don't guess, observe, classify, trace.

## Before You Start

Read the design system reference for the visual tokens and established patterns:
```
Read: .claude/skills/frontend-bugfix/references/architecture-guide.md
```

This reference contains the component tree, data flow architecture, and design tokens. Consult it when you need to understand how the pieces connect.

## Step 1: Reproduce and Observe

This step is where the skill adds the most value. Playwright-based reproduction with console capture and screenshots gives you concrete evidence instead of guessing. Start every bug fix by establishing the current state. Don't jump to code.

1. **Check if the dev server is running.** If not, start it:
   ```bash
   cd frontend && npm run dev
   ```

2. **Open the page in a browser** using Playwright to capture what's actually happening:
   - Take a screenshot of the broken page
   - Capture console messages (errors, warnings)
   - Capture network requests (failed API calls)

   ```
   Use Playwright MCP tools:
   - browser_navigate to the affected route
   - browser_console_messages to capture errors/warnings
   - browser_network_requests to see failed API calls
   - browser_take_screenshot for visual state
   ```

3. **Record what you find.** Write down:
   - The exact error message(s), if any
   - What the page shows vs what it should show
   - Which route/page is affected

This step is non-negotiable. Skipping reproduction leads to fixing symptoms instead of causes.

## Step 2: Classify the Bug

Every frontend bug falls into one of four categories. The classification determines which trace path to follow.

| Category | Signals | Example |
|---|---|---|
| **API Error** | Network tab shows 4xx/5xx; `toast.error()` fires; data is `undefined` when it shouldn't be | "Failed to load margin history" toast appears |
| **Render Error** | Console shows React error with component stack trace; blank/white page; "Cannot read property of undefined" | Page crashes when SKU has no cost envelope |
| **State Error** | UI shows stale data; component doesn't update after mutation; filter doesn't work | SKU list doesn't refresh after pausing a SKU |
| **Style Error** | Layout broken; colors wrong; text invisible; elements overlapping; responsive breakpoint issues | KPI cards stack vertically instead of horizontal grid |

If you see multiple signals, start with the **earliest** one in the chain (API errors cause render errors, which can look like style errors).

## Step 3: Trace — Follow the Right Path

### API Error Path

The data flow is: `api/client.ts` (fetch wrapper) -> `api/{module}.ts` (React Query hook) -> Page component.

1. **Check the network request.** What URL was called? What status code came back? What was the response body?
2. **Match the URL to the API hook.** Look in `frontend/src/api/` for the hook that calls that endpoint.
3. **Check the hook's query key and parameters.** Is the right ID being passed? Is the query enabled when it should be?
4. **Check the backend.** If the request reaches the backend but returns an error, the fix is in the Kotlin code, not the frontend.

Common API issues in this project:
- Missing `enabled: !!id` guard on queries that depend on a selected item
- Wrong query key causing cache misses or stale data
- Backend returning a different response shape than `api/types.ts` expects

### Render Error Path

React render errors produce component stack traces in the console. Read them bottom-to-top (like Java stack traces).

1. **Find the component that threw.** The stack trace names the component and often the line.
2. **Check for unguarded data access.** The #1 cause is accessing properties on data that hasn't loaded yet:
   ```tsx
   // Bug: data might be undefined during loading
   <span>{data.name}</span>

   // Fix: guard with optional chaining or loading check
   <span>{data?.name}</span>
   ```
3. **Check for missing loading/empty states.** Every page must handle three states:
   - **Loading:** Show `<Skeleton />` components
   - **Empty:** Show a message (often with `<SpriteScene>`)
   - **Populated:** Show the actual content

   If any of these is missing, the component will crash or show a blank page during that state.

4. **Check for missing `key` props on lists.** React warns about this. Use the entity's `id`, never the array index:
   ```tsx
   // Warning: missing key
   {skus.map(sku => <SkuRow sku={sku} />)}

   // Fixed
   {skus.map(sku => <SkuRow key={sku.id} sku={sku} />)}
   ```

### State Error Path

State issues mean the UI doesn't reflect the current data.

1. **Check query invalidation.** After a mutation, the related queries must be invalidated:
   ```tsx
   // In api/{module}.ts
   onSuccess: () => {
     queryClient.invalidateQueries({ queryKey: ["skus"] });
   },
   ```
   If the query key pattern doesn't match, the old data stays cached.

2. **Check `useState` vs derived data.** If data can be computed from query results, use `useMemo`, not `useState`. `useState` creates a copy that gets stale:
   ```tsx
   // Bug: stale copy
   const [count, setCount] = useState(skus.length);

   // Fix: derived from source
   const count = useMemo(() => skus?.length ?? 0, [skus]);
   ```

3. **Check conditional rendering logic.** Does the component check the right state value? Common mistake: checking `isLoading` but not `isError`.

### Style Error Path

Style bugs are about visual output not matching intent.

1. **Check for hardcoded values.** All colors must use CSS variables from `index.css`. All fonts must be one of the three established families. Hardcoded hex values or font names are bugs:
   ```tsx
   // Bug
   style={{ color: "#e5a00d" }}
   // Fix
   style={{ color: "var(--accent)" }}
   ```

   **Exception:** Recharts SVG elements (`stopColor` in gradients, `stroke` on chart lines) cannot resolve CSS variables. Hardcoded hex values that match the design tokens are acceptable inside Recharts `<defs>`, `<Area>`, `<Line>`, etc. Map them from the token hex values in `index.css` (e.g., `#34d399` for `--profit`, `#e5a00d` for `--accent`).

2. **Check Tailwind vs inline style usage.** This project uses:
   - **Tailwind** for layout: `flex`, `grid`, `gap-*`, `p-*`, `rounded-*`, `items-center`
   - **Inline `style={}`** for design tokens: colors, fonts, font-sizes, borders with CSS variables
   - Never mix — don't put colors in Tailwind classes, don't put layout in inline styles

3. **Check responsive breakpoints.** KPI grids use `grid-cols-4` or `grid-cols-1 md:grid-cols-2`. If a layout breaks at certain widths, the grid column count is likely wrong.

4. **Check z-index and overflow.** The sidebar is `fixed` with `w-64`, and main content has `ml-64`. If content appears under the sidebar, the margin is missing. If content is cut off, check `overflow-y-auto` on the content area.

## Step 4: Fix

Apply the minimum necessary change. Frontend fixes should be surgical.

**Constraints — these are non-negotiable:**
- Never modify files in `frontend/src/components/ui/` — these are shadcn-generated primitives. If a UI primitive needs changing, use `npx shadcn add <component>` to regenerate it.
- Never hardcode colors, fonts, or spacing values — always use CSS variables from `index.css` or Tailwind utilities.
- Never add new npm dependencies without asking the user first.
- Never change the API contract (`api/types.ts`) without confirming the backend actually changed.
- If you add a loading state, use `<Skeleton />` from `@/components/ui/skeleton`.
- If you add an empty state, use `<SpriteScene>` with an appropriate sprite and message.

## Step 5: Verify

After fixing, confirm the bug is actually resolved and nothing else broke.

1. **Check the fixed page:**
   ```
   Use Playwright MCP tools:
   - browser_navigate to the affected route
   - browser_console_messages — should be clean (no errors, no new warnings)
   - browser_take_screenshot — should look correct
   ```

2. **Check adjacent pages.** If you changed a shared component (anything in `components/` outside `ui/`), verify pages that use it still render correctly.

3. **Run the TypeScript compiler** to catch type errors:
   ```bash
   cd frontend && npx tsc --noEmit
   ```

4. **Run the linter:**
   ```bash
   cd frontend && npm run lint
   ```

## Common Patterns You'll See

These are project-specific patterns that come up repeatedly:

| Pattern | Where | What It Does |
|---|---|---|
| `useQuery` + `queryKey` | `api/*.ts` | Cached data fetching — key determines cache identity |
| `useMutation` + `invalidateQueries` | `api/*.ts` | Write + cache bust — invalidation triggers refetch |
| `isLoading` / `isError` / `data` | Page components | Three states from React Query — all three must be handled |
| `style={{ fontFamily: "'Bricolage Grotesque'" }}` | Page headings | Display font for titles only |
| `style={{ fontFamily: "'Martian Mono'" }}` | Numeric data | Monospace font for numbers, dates, data values |
| `style={{ color: "var(--text-secondary)" }}` | Labels, metadata | Muted text for secondary information |
| `<StatusBadge status={...} />` | Tables, cards | Semantic color-coded badge for entity states |
| `<KpiCard label={...} value={...} accentColor={...} />` | Page tops | Summary metric card with colored left border |
| `<DataTable columns={...} data={...} />` | List pages | Generic typed table with hover effects |
| `<Skeleton className="h-24 rounded-lg" />` | Loading states | Placeholder rectangle during data fetch |
