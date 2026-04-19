# Nathan Report

```yaml
name: nathan-report
description: >
  Generate a CEO/COO-level progress memo after completing a Linear issue, feature request, or
  significant piece of work. Produces a scannable .md document in docs/nathan-reports/ that
  explains what was accomplished in plain business English — no code, no jargon. Written for
  a project manager who thinks strategically but isn't deep in the technical details.
  Use this skill when: the user says "nathan report", "write a report for Nathan", "executive
  summary", "progress memo", "stakeholder update", or after completing an FR or Linear issue
  and wants to document what happened for non-technical stakeholders. Also use when the user
  says things like "let's update Nathan", "write up what we did", or "summarize this for the team".
```

## Purpose

Nathan is the project's PM/COO. He thinks in terms of business outcomes, timelines, risks, and strategic priorities — not in terms of adapters, constructors, or Spring annotations. This skill bridges that gap by translating a technical session into something he can read in 2 minutes and walk away understanding:
- What changed and why it matters to the business
- What's now possible that wasn't before
- What's blocked, at risk, or needs a decision
- What comes next

## How It Works

You have access to the full conversation history. Reconstruct what happened — the work completed, decisions made, problems encountered, and outcomes achieved. Also check Linear for issue context if available.

The golden rule: **if Nathan would need to Google a *technical implementation* term to understand the sentence, rewrite the sentence.** Terms like "adapter", "constructor", "bean", "Spring profile" should never appear. Instead, describe *what the system can do now* that it couldn't before.

**Important nuance:** Nathan is financially sharp. He understands margin thresholds (50% gross / 30% net floors), CAC variance, stress test parameters, reserve ratios (10-15%), chargeback/refund rate triggers, and the zero-capital business model inside and out — he's the one who caught that the system was incorrectly tracking budget instead of operating zero-capital (PM-007). Use financial specifics freely: margin percentages, cost envelope components, kill rule thresholds, reserve health. Only translate *engineering implementation* concepts to plain English. Don't dumb down the business layer.

## Step 1: Gather Context

Scan the conversation for:
- Which Linear issue(s) or FR(s) were completed
- The business problem that was solved
- Key decisions made (especially pivots or scope changes)
- Blockers removed or new blockers discovered
- What was tested / validated
- What work is now unblocked

If Linear MCP tools are available, pull the issue details for richer context:
```
mcp__plugin_linear_linear__get_issue(id: "RAT-XX")
```

## Step 2: Determine the Next Report Number

```bash
ls docs/nathan-reports/NR-*.md 2>/dev/null | sort -V | tail -1
```

If no reports exist yet, start at NR-001. Otherwise increment from the highest.

Create the directory if needed:
```bash
mkdir -p docs/nathan-reports
```

## Step 3: Generate a Slug

Create a short kebab-case slug from the work completed (e.g., `demand-signal-api-pivot`, `shopify-listing-adapter`, `ci-reliability-improvements`). Keep it under 50 characters. It should read like a newspaper headline — someone scanning the directory should immediately know what the report covers.

## Step 4: Write the Report

Save to `docs/nathan-reports/NR-{NNN}-{slug}.md` using this structure:

```markdown
# NR-{NNN}: {Title — plain English, outcome-focused}

**Date:** {YYYY-MM-DD}
**Linear:** {RAT-XX (linked if URL available)}
**Status:** {Completed | In Progress | Blocked}

---

## TL;DR

{2-3 sentences. What was accomplished and why it matters. Write this for someone who
will only read this section. Lead with the outcome, not the activity.}

## What Changed

{Describe what the system can do now that it couldn't before. Use business language:
"The system can now automatically discover trending products from YouTube and Reddit"
not "Added YouTubeDataAdapter and RedditDemandAdapter implementing DemandSignalProvider".

Use bullet points. Each bullet should be one clear capability or change.
Group related changes if there are many.}

## Why This Matters

{Connect the change to business goals, the product roadmap, or operational readiness.
What gap does this close? What risk does it reduce? How does this move us closer to
the autonomous commerce engine vision?

If there was a pivot or strategic decision, explain the reasoning in business terms.
For example: "We dropped Amazon's product data API because it requires 10 qualifying
sales per month to maintain access — a chicken-and-egg problem for a system that hasn't
launched yet. YouTube and Reddit provide stronger demand signals at zero cost."}

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| {area 1} | {Done/In Progress/Blocked/Not Started} | {brief note} |
| {area 2} | ... | ... |

{This table gives Nathan a glanceable view of where things stand.
Include the completed work AND adjacent/dependent work areas.}

## What's Next

{What is now unblocked? What should be prioritized? List in recommended order
with brief rationale. This is where Nathan gets actionable information for
planning and prioritization conversations.}

- **{Next item 1}** — {why it's next, what it enables}
- **{Next item 2}** — {why it matters}

## Risks & Decisions Needed

{Only include this section if there are genuine risks, open questions, or decisions
that need stakeholder input. If everything is clean, omit this section entirely —
don't manufacture risks for completeness.

Format as bullets with clear ask:}
- **{Risk/Decision}:** {context} → **Ask:** {what Nathan needs to do or decide}

## Session Notes

{Optional — brief color commentary on how the work went. Surprising findings,
tools that helped, things that took longer than expected. Keep it to 2-3 bullets.
This gives Nathan a feel for velocity and complexity without technical details.}
```

## Writing Guidelines

**Tone:** Professional but conversational. This is an internal memo between teammates, not a board presentation. Write like you're explaining to a smart friend who doesn't code.

**Length:** Aim for 1 page when rendered. Nathan should be able to read the whole thing in under 2 minutes. If you're going long, cut the Session Notes section first, then trim Why This Matters.

**No code. Ever.** Not even file paths unless they're essential context (like "the configuration file for CI pipelines"). If you catch yourself writing backticks, stop and rephrase.

**Technical jargon translation guide** (only applies to engineering terms — financial/business terms are fine as-is):
| Instead of... | Write... |
|---------------|----------|
| "adapter" / "provider" | "integration" or "connection to [service]" |
| "bean instantiation" | "system startup" |
| "@Value defaults" | "configuration safety" |
| "WireMock tests" | "automated API verification" |
| "Spring context" | "the application" |
| "CI pipeline" | "our automated build checks" |
| "refactored" | "reorganized" or "simplified" |
| "merged PR" | "shipped" or "completed" |
| "regression" | "something that used to work broke" |
| "edge case" | "unusual scenario" |

**Financial terms — use freely:** Net margin, gross margin, CAC, stress test parameters (2x shipping, +15% CAC, etc.), reserve ratios, refund/chargeback rate thresholds, cost envelope components, fully-burdened cost, risk-adjusted return. Nathan knows these cold.

**Metrics when available:** If the session produced quantifiable results (tests added, endpoints created, sources integrated), include them — Nathan likes numbers. Frame them as outcomes: "4 data sources now active (was 3)" not "22 new unit tests".

## Storytelling & Voice

Nathan reports work best as **narratives**, not bullet-point summaries. Nathan should want to read it, not skim past it.

### Tell the story

When a session involves multiple attempts, failures, pivots, or discoveries, structure the report as a story arc. Use a "The Story of X" section. Walk through what happened in sequence — the first attempt, what went wrong, the second attempt, what was learned. This is more engaging than a flat list and helps Nathan understand *why* decisions were made.

Example: NR-006 told "The Story of Two Implementations" — Attempt 1 shipped a quantity bug, Attempt 2 fixed it but shipped a null-string bug, and the analysis revealed the workflow itself was the root cause. The narrative makes the conclusion ("we need to fix the workflow, not the feature") feel inevitable rather than arbitrary.

### Hard-hitting facts, not hedging

Don't soften uncomfortable truths. "65 tests, 0 real coverage" is more powerful than "some tests had quality issues." Let the data speak. If something failed ironically (a warning comment 5 lines above the bug, 65 tests missing the one thing that broke), lean into it. Nathan appreciates intellectual honesty about failures — it builds trust.

Bad: "The test coverage had some gaps that we're addressing."
Good: "65 automated quality checks. Zero caught the bug. The Unblocked reviewer caught it in 30 seconds."

### Explain concepts with analogies first, then carry the shorthand

When introducing a concept Nathan hasn't seen before (like "test theater" or "state machine architecture"), explain it with a concrete analogy from business, finance, or operations — his domain. Once the concept clicks, use the shorthand for the rest of the document.

- "Think of it like a home inspection that writes PASS without looking at the house" → then use "test theater" freely
- "Like a financial model that's accurate but optional to read" → then use "strategy engine" freely
- "Like a vending machine that can't dispense without payment — the structure prevents the error, not willpower" → then use "state machine" freely

### Philosophical depth when earned

When there's a deeper lesson behind the technical findings, name it explicitly. "You can automate execution, but you can't automate taste" is the kind of insight that sticks. Frame failures as learning, not as setbacks: "This is a step backward that gives us total clarity on how to move forward." Nathan thinks strategically — give him the strategic takeaway, not just the status update.

### Visuals tell the story faster

Use Mermaid-generated PNG diagrams to make comparisons and metrics visceral. Good diagram types for Nathan reports:

- **Flowcharts** comparing two approaches side by side (v1 workflow vs v2 workflow)
- **Pie charts** breaking down composition (what "65 tests" actually contains)
- **Timeline diagrams** showing the story arc (two attempts and a planned third)
- **Bar charts** for stark metric contrasts (23 features / 0 bugs vs 1 feature / 1 bug)
- **Architecture diagrams** for proposed solutions (state machine with human gates)

Render with `mmdc -s 2 -b white` for retina quality. Store in `docs/nathan-reports/assets/`. Reference with relative paths in markdown: `![Description](assets/filename.png)`.

For emails, host images on GitHub via raw URLs from the branch and wrap in `<img>` tags with `border`, `border-radius`, and `background-color` for dark mode resilience.

## Step 5: Phase 1 — Draft and Review

After writing the file to `docs/nathan-reports/`:

1. Show the user the **TL;DR** and **Status Snapshot** sections inline
2. Tell them the full report is saved at the file path
3. **Stop and wait for approval.** Say something like: "Report drafted. Take a look and let me know if the tone/content is right, or if you want changes. When you're ready, I'll email it to Nathan."
4. If the user requests edits, make them and re-present
5. Do NOT proceed to email until the user explicitly approves (e.g., "looks good", "send it", "go ahead")

## Step 6: Phase 2 — Email Delivery

Once the user approves the draft:

1. **Create a Gmail draft** using `mcp__claude_ai_Gmail__gmail_create_draft`:
   - **Subject:** `[Auto Shipper] NR-{NNN}: {Title}`
   - **To:** Nathan's email — check memory first (`user_nathan_profile.md` has his email). Only ask the user if not found in memory.
   - **Body:** Convert the markdown report to styled HTML following the Email Design System below. The email should feel like a polished internal newsletter — professional but not corporate, scannable on a phone, engaging without being distracting.
2. Tell the user the draft is ready in Gmail for final review and send
3. The file in `docs/nathan-reports/` stays as a local reference copy

If Gmail MCP is not available, tell the user to forward the file manually and note which email to send to.

### Email Design System

The email should feel like a well-designed internal newsletter (think Morning Brew or TLDR). All styling must use **inline CSS** — email clients strip `<style>` blocks.

#### Theme — Dark (default)

**The Auto Shipper Nathan reports always use dark theme.** Do not use a light palette. Light-theme variants were the original draft but were replaced after NR-012 — dark is the house style.

Force dark mode on every client:

1. **Wrap the email body** in a `<div>` with `style="background-color: #0F0E0D; color: #FAFAF9; color-scheme: dark only; -webkit-color-scheme: dark only;"`. This prevents Apple Mail / iOS from auto-inverting to a weird mid-tone.
2. **Every text element must have explicit inline `color`.** Gmail only inverts elements without explicit color. If `color` is set inline, Gmail leaves it alone. Never rely on inherited color.
3. **Every container must have explicit `background-color`** — don't let any div inherit from body. Set `background-color` on every section wrapper, callout box, table cell, step card.
4. **Images need a framed container** — `border: 1px solid #44403C; border-radius: 8px; padding: 4px; background-color: #1C1917;` so PNGs with white backgrounds don't float as bright rectangles.
5. **Colors that survive dark-mode clients:** amber (`#F59E0B`, `#FCD34D`), green (`#10B981`, `#6EE7B7`), red (`#EF4444`, `#FCA5A5`). Avoid pale muted tones for critical content — use them only for labels.
6. **Host images on GitHub** — push PNGs to the branch, reference via `https://raw.githubusercontent.com/{org}/{repo}/{branch}/docs/nathan-reports/{file}.png`. Always include descriptive `alt` text.

#### Brand Colors (dark palette)

| Token | Hex | Usage |
|-------|-----|-------|
| Amber primary | `#F59E0B` | H1 bottom border, link color, section accents |
| Amber bright | `#FCD34D` | Emphasis, code keywords, "Now" labels |
| Page background | `#0F0E0D` | Outermost wrapper |
| Surface 1 (banner, body, table base) | `#1C1917` | Email banner, body background, footer |
| Surface 2 (callouts, cards, alt rows) | `#292524` | TL;DR callout, step cards, decision callout, alternating table rows |
| Border | `#44403C` | Table header underline, image frame |
| Text primary | `#FAFAF9` | Headings, strong-emphasis body |
| Text body | `#E7E5E4` | Regular paragraph + list text |
| Text secondary | `#D6D3D1` | Inside card bodies |
| Text muted | `#A8A29E` | Dates, labels, table notes |
| Text very muted | `#78716C` | Footer copyright-style text |
| Success green | `#10B981` | Done status dot |
| Success green bright | `#6EE7B7` | Success code snippets, PASS output |
| Success green deep | `#064E3B` | Financial-posture callout background |
| Risk red | `#EF4444` | Not-started + blocked dots |
| Risk red bright | `#FCA5A5` | "Never do X" emphasis inside decision callout |
| Risk red deep | `#7F1D1D` | (reserved — use for deep-red callout backgrounds if ever needed) |
| Code-snippet background | `#1C1917` | Inline `code` backgrounds |

#### Typography

All fonts use the stack: `'Inter', 'Helvetica Neue', Helvetica, Arial, sans-serif` — Inter is widely supported and clean on mobile.

| Element | Size | Weight | Color | Extra |
|---------|------|--------|-------|-------|
| Report title (H1) | 28px | 700 | `#FAFAF9` | 3px solid `#F59E0B` bottom border, 12px bottom padding |
| Section header (H2) | 20px | 600 | `#FAFAF9` | 24px top margin, 8px bottom margin |
| Subsection (H3) | 16px | 600 | `#FAFAF9` | 6px top / 10px bottom margin inside step cards |
| Body text | 15px | 400 | `#E7E5E4` | Line-height 1.6 |
| Secondary body | 14px | 400 | `#D6D3D1` | Inside step cards |
| Muted label | 11px-13px | 600-700 | `#A8A29E` or `#F59E0B` | Uppercase, letter-spacing 0.5–1px |
| TL;DR text | 16px | 400 | `#FAFAF9` | Inside amber-border callout |

#### Layout

- Max width: `640px`, centered with `margin: 0 auto`
- Body padding: `24px` on sides (comfortable on phone)
- Section spacing: `32px` between major sections

#### Header Banner

```html
<div style="background-color: #1C1917; padding: 16px 24px; border-radius: 8px 8px 0 0; border-bottom: 1px solid #292524;">
  <span style="color: #F59E0B; font-size: 13px; font-weight: 600; letter-spacing: 1px; text-transform: uppercase; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">Auto Shipper — Progress Report</span><br>
  <span style="color: #A8A29E; font-size: 13px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">NR-{NNN} · {Date}</span>
</div>
```

#### Meta-Line Under H1

Under the H1, include a line with Linear link · PR link · status dots:

```html
<p style="font-size: 13px; color: #A8A29E; margin: 0 0 20px 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
  <span style="color: #A8A29E;">Linear:</span> <a href="{url}" style="color: #F59E0B; text-decoration: none;">RAT-{NN}</a>
  <span style="color: #57534E;"> · </span>
  <a href="{pr-url}" style="color: #F59E0B; text-decoration: none;">PR #{NN}</a>
  <span style="color: #57534E;"> · </span>
  <span style="color: #10B981; font-weight: 600;">● Completed</span>
</p>
```

#### TL;DR Callout Box

```html
<div style="background-color: #292524; border-left: 4px solid #F59E0B; padding: 16px 20px; border-radius: 4px; margin: 20px 0;">
  <span style="font-size: 12px; font-weight: 600; color: #F59E0B; text-transform: uppercase; letter-spacing: 0.5px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">TL;DR</span>
  <p style="font-size: 16px; color: #FAFAF9; line-height: 1.6; margin: 8px 0 0 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">...</p>
</div>
```

#### Step-Card (for operator checklists / numbered procedures)

Use a stack of step-cards when the report contains a multi-step procedure. Each card has a timing badge, section title, and ordered list. Left border color signals progression — amber for prep steps, green for the payoff step, neutral gray for cleanup/post-test:

```html
<div style="background-color: #292524; border-radius: 8px; padding: 20px; margin-bottom: 12px; border-left: 3px solid #F59E0B;">
  <span style="font-size: 11px; font-weight: 700; color: #F59E0B; text-transform: uppercase; letter-spacing: 0.8px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">Step 1 · ~20 min</span>
  <h3 style="font-size: 16px; font-weight: 600; color: #FAFAF9; margin: 6px 0 10px 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">Card Title</h3>
  <ol style="font-size: 14px; color: #D6D3D1; line-height: 1.6; margin: 0; padding-left: 22px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
    <li>Step body...</li>
  </ol>
</div>
```

Left-border color choices: `#F59E0B` (amber — prep/setup), `#10B981` (green — the "drive the test" or payoff step), `#A8A29E` (gray — cleanup / post-test).

#### Inline Code Snippets

```html
<code style="color: #FCD34D; background-color: #1C1917; padding: 1px 5px; border-radius: 3px; font-size: 13px;">some_value</code>
```

Use `#FCD34D` amber for most code. Switch to `#6EE7B7` (green) when the snippet represents a successful / expected-good output (e.g. a `PASS` string, a known-safe test card number).

#### Financial-Posture Callout (green)

Use for zero-cost / zero-risk claims. Green-tinted body with amber emphasis is reserved for one "hero claim" line:

```html
<div style="background-color: #064E3B; border-left: 4px solid #10B981; padding: 14px 18px; border-radius: 4px; margin: 16px 0;">
  <p style="font-size: 15px; color: #A7F3D0; line-height: 1.6; margin: 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
    <strong style="color: #6EE7B7;">Out-of-pocket costs before the test: $0.</strong> ...context...
  </p>
</div>
```

#### Status Indicators

Use colored dots for inline status. These render in all email clients:

```html
<!-- Done -->
<span style="color: #10B981; font-weight: 600;">● Done</span>

<!-- Not Started -->
<span style="color: #EF4444; font-weight: 600;">● Not Started</span>

<!-- In Progress -->
<span style="color: #F59E0B; font-weight: 600;">● In Progress</span>

<!-- Blocked -->
<span style="color: #EF4444; font-weight: 600;">◆ Blocked</span>
```

#### Tables

Status snapshot tables use `#1C1917` base rows and `#292524` alternating rows. Every `<td>` and `<th>` MUST carry explicit `background-color` — Gmail will not honor row-level background on table cells.

```html
<table style="width: 100%; border-collapse: collapse; font-size: 14px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif; background-color: #1C1917;">
  <thead>
    <tr style="border-bottom: 2px solid #44403C;">
      <th style="text-align: left; padding: 10px 12px; color: #A8A29E; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; background-color: #1C1917;">Area</th>
      <th style="text-align: left; padding: 10px 12px; color: #A8A29E; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; background-color: #1C1917;">Status</th>
      <th style="text-align: left; padding: 10px 12px; color: #A8A29E; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; background-color: #1C1917;">Notes</th>
    </tr>
  </thead>
  <tbody>
    <tr style="border-bottom: 1px solid #292524;">
      <td style="padding: 10px 12px; color: #E7E5E4; background-color: #1C1917;">...</td>
      <td style="padding: 10px 12px; background-color: #1C1917;"><span style="color: #10B981; font-weight: 600;">● Done</span></td>
      <td style="padding: 10px 12px; color: #A8A29E; background-color: #1C1917;">...</td>
    </tr>
    <tr style="border-bottom: 1px solid #292524;">
      <td style="padding: 10px 12px; color: #E7E5E4; background-color: #292524;">...</td>
      <td style="padding: 10px 12px; background-color: #292524;"><span style="color: #EF4444; font-weight: 600;">● Not Started</span></td>
      <td style="padding: 10px 12px; color: #A8A29E; background-color: #292524;">...</td>
    </tr>
  </tbody>
</table>
```

#### Risk / Decision Callout

```html
<div style="background-color: #292524; border-left: 4px solid #EF4444; padding: 16px 20px; border-radius: 4px; margin: 24px 0;">
  <span style="font-size: 12px; font-weight: 600; color: #FCA5A5; text-transform: uppercase; letter-spacing: 0.5px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">Decisions Needed</span>
  <p style="font-size: 15px; color: #FAFAF9; line-height: 1.6; margin: 10px 0 0 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
    <strong>{Decision title}:</strong> {context} <em style="color: #FCD34D;">Ask: {what Nathan needs to do}</em>
  </p>
</div>
```

#### Images (Diagrams)

**Always use PNG for email, never SVG.** Mermaid-generated SVGs use `<foreignObject>` for text, which Gmail and most email clients strip — resulting in shapes without labels. Render with `mmdc -s 2` for 2x retina resolution and `-b white` for a clean background.

Embed as hosted images via `<img>` tag. Use `width="100%"` and `max-width` for mobile responsiveness:

```html
<img src="cid:pipeline-status" alt="Autonomous Pipeline Status" style="width: 100%; max-width: 600px; height: auto; margin: 16px 0; border-radius: 4px;">
```

When using Gmail draft API, attach images as inline attachments with matching Content-ID, or host them at a stable URL the recipient can access.

#### Footer

```html
<div style="padding: 16px 24px; border-top: 1px solid #292524; background-color: #1C1917; border-radius: 0 0 8px 8px;">
  <p style="font-size: 12px; color: #78716C; margin: 0; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
    Commerce Engine · Auto Shipper AI<br>
    Generated from session work on {date}.
  </p>
</div>
```

#### Full Email Skeleton (dark)

```html
<body style="margin:0;padding:0;background-color:#0F0E0D;">
<div style="max-width: 640px; margin: 0 auto; font-family: 'Inter', 'Helvetica Neue', Helvetica, Arial, sans-serif; background-color: #0F0E0D; color: #FAFAF9; color-scheme: dark only; -webkit-color-scheme: dark only;">

  <!-- Header banner -->
  <div style="background-color: #1C1917; padding: 16px 24px; border-radius: 8px 8px 0 0; border-bottom: 1px solid #292524;">
    <span style="color: #F59E0B; font-size: 13px; font-weight: 600; letter-spacing: 1px; text-transform: uppercase;">Auto Shipper — Progress Report</span><br>
    <span style="color: #A8A29E; font-size: 13px;">NR-{NNN} · {Date}</span>
  </div>

  <!-- Body -->
  <div style="padding: 24px; background-color: #1C1917;">

    <h1 style="font-size: 28px; font-weight: 700; color: #FAFAF9; margin: 0 0 12px 0; padding-bottom: 12px; border-bottom: 3px solid #F59E0B;">{Title}</h1>

    <!-- Meta-line: Linear · PR · status -->
    <p style="font-size: 13px; color: #A8A29E; margin: 0 0 20px 0;">
      <span style="color: #A8A29E;">Linear:</span> <a href="{url}" style="color: #F59E0B; text-decoration: none;">RAT-{NN}</a>
      <span style="color: #57534E;"> · </span>
      <span style="color: #10B981; font-weight: 600;">● Completed</span>
    </p>

    <!-- TL;DR callout (dark surface-2 with amber left border) -->
    <div style="background-color: #292524; border-left: 4px solid #F59E0B; padding: 16px 20px; border-radius: 4px; margin: 20px 0;">
      <span style="font-size: 12px; font-weight: 600; color: #F59E0B; text-transform: uppercase; letter-spacing: 0.5px;">TL;DR</span>
      <p style="font-size: 16px; color: #FAFAF9; line-height: 1.6; margin: 8px 0 0 0;">{summary text}</p>
    </div>

    <!-- Images: framed to prevent bright-rectangle effect -->
    <img src="https://raw.githubusercontent.com/{org}/{repo}/{branch}/docs/nathan-reports/{file}.png"
         alt="{Descriptive fallback}"
         style="width: 100%; max-width: 600px; height: auto; margin: 16px 0; border-radius: 8px; border: 1px solid #44403C; padding: 4px; background-color: #1C1917;">

    <!-- H2 sections, step-cards, tables, callouts follow -->
    <!-- IMPORTANT: every element must have explicit inline color and background-color -->

  </div>

  <!-- Footer -->
  <div style="padding: 16px 24px; border-top: 1px solid #292524; background-color: #1C1917; border-radius: 0 0 8px 8px;">
    <p style="font-size: 12px; color: #78716C; margin: 0;">Commerce Engine · Auto Shipper AI<br>Generated from session work on {date}.</p>
  </div>

</div>
</body>
```

**Reference implementation:** the NR-012 email draft (Gate-Zero) is the canonical worked example. When in doubt about a component, mirror how it's rendered there.
