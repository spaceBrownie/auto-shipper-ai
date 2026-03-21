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

#### Dark Mode Compatibility

Email dark mode varies wildly across clients. Use this strategy to look good everywhere:

1. **Force light mode where supported** — wrap the entire email body in a `<div>` with both `style="background-color: #FFFFFF; color: #292524;"` AND add these attributes to prevent auto-inversion:
   - Apple Mail / iOS: respects `style="color-scheme: light only; -webkit-color-scheme: light only;"`
   - Outlook: respects inline backgrounds
   - Gmail: ignores meta tags and auto-inverts — but respects explicit inline `color` and `background-color`

2. **Every text element must have explicit inline `color`** — Gmail dark mode only inverts elements without explicit color. If `color` is set inline, Gmail leaves it alone. Never rely on inherited color.

3. **Every container must have explicit `background-color`** — don't let any div inherit from body. Set `background-color` on every section wrapper, callout box, table cell, etc.

4. **Images need a visible frame** — in dark mode, white-background PNGs float as bright rectangles. Add `border: 1px solid #E7E5E4; border-radius: 8px; padding: 4px; background-color: #FFFFFF;` to `<img>` tags so the image has a contained look in both themes.

5. **Amber/green status colors survive dark mode** — these are bright enough to read on both light and dark backgrounds. Red (`#EF4444`) also survives. Avoid pale colors like `#78716C` for critical content (use it only for labels, not key data).

6. **Host images on GitHub** — push PNGs to the branch, then reference via `https://raw.githubusercontent.com/{org}/{repo}/{branch}/docs/nathan-reports/{file}.png`. Always include descriptive `alt` text as fallback.

#### Brand Colors

| Token | Hex | Usage |
|-------|-----|-------|
| Amber (primary accent) | `#F59E0B` | H1 bottom border, key metric highlights, link color |
| Dark surface | `#1C1917` | Email header/banner background |
| Warm white | `#FAFAF9` | Body background |
| Stone text | `#292524` | Body text |
| Muted stone | `#78716C` | Secondary text, dates, labels |
| Green (done) | `#10B981` | Status indicators for completed work |
| Red (gap/risk) | `#EF4444` | Status indicators for blockers or risks |

#### Typography

All fonts use the stack: `'Inter', 'Helvetica Neue', Helvetica, Arial, sans-serif` — Inter is widely supported and clean on mobile.

| Element | Size | Weight | Color | Extra |
|---------|------|--------|-------|-------|
| Report title (H1) | 28px | 700 | `#292524` | 3px solid `#F59E0B` bottom border, 12px bottom padding |
| Section header (H2) | 20px | 600 | `#292524` | 24px top margin, 8px bottom margin |
| Subsection (H3) | 16px | 600 | `#78716C` | Uppercase, letter-spacing 0.5px |
| Body text | 15px | 400 | `#292524` | Line-height 1.6 |
| TL;DR text | 16px | 400 | `#292524` | Inside a callout box (see below) |

#### Layout

- Max width: `640px`, centered with `margin: 0 auto`
- Body padding: `24px` on sides (comfortable on phone)
- Section spacing: `32px` between major sections

#### Header Banner

A dark strip at the top of the email with the report identifier:

```html
<div style="background-color: #1C1917; padding: 16px 24px; border-radius: 8px 8px 0 0;">
  <span style="color: #F59E0B; font-size: 13px; font-weight: 600; letter-spacing: 1px; text-transform: uppercase; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">Auto Shipper — Progress Report</span>
  <br>
  <span style="color: #A8A29E; font-size: 13px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">NR-{NNN} · {Date}</span>
</div>
```

#### TL;DR Callout Box

The TL;DR should stand out as a highlighted summary block:

```html
<div style="background-color: #FFFBEB; border-left: 4px solid #F59E0B; padding: 16px 20px; border-radius: 4px; margin: 20px 0;">
  <span style="font-size: 12px; font-weight: 600; color: #D97706; text-transform: uppercase; letter-spacing: 0.5px;">TL;DR</span>
  <p style="font-size: 16px; color: #292524; line-height: 1.6; margin: 8px 0 0 0;">...</p>
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

Status snapshot tables should be clean with subtle borders and alternating row shading:

```html
<table style="width: 100%; border-collapse: collapse; font-size: 14px; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif;">
  <thead>
    <tr style="border-bottom: 2px solid #E7E5E4;">
      <th style="text-align: left; padding: 10px 12px; color: #78716C; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;">Area</th>
      <th style="text-align: left; padding: 10px 12px; color: #78716C; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;">Status</th>
      <th style="text-align: left; padding: 10px 12px; color: #78716C; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;">Notes</th>
    </tr>
  </thead>
  <tbody>
    <tr style="border-bottom: 1px solid #F5F5F4;">
      <td style="padding: 10px 12px;">...</td>
      <td style="padding: 10px 12px;"><span style="color: #10B981; font-weight: 600;">● Done</span></td>
      <td style="padding: 10px 12px; color: #78716C;">...</td>
    </tr>
    <tr style="border-bottom: 1px solid #F5F5F4; background-color: #FAFAF9;">
      <!-- alternating row -->
    </tr>
  </tbody>
</table>
```

#### Risk / Decision Callout

For the "Risks & Decisions Needed" section, use a red-tinted callout:

```html
<div style="background-color: #FEF2F2; border-left: 4px solid #EF4444; padding: 16px 20px; border-radius: 4px; margin: 20px 0;">
  <span style="font-size: 12px; font-weight: 600; color: #DC2626; text-transform: uppercase; letter-spacing: 0.5px;">Decision Needed</span>
  <p style="font-size: 15px; color: #292524; line-height: 1.6; margin: 8px 0 0 0;">...</p>
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

A minimal footer with project context:

```html
<div style="border-top: 1px solid #E7E5E4; padding-top: 16px; margin-top: 32px;">
  <p style="font-size: 12px; color: #A8A29E; font-family: 'Inter', 'Helvetica Neue', Arial, sans-serif; margin: 0;">
    Commerce Engine · Auto Shipper AI<br>
    This report was generated from session work on {date}.
  </p>
</div>
```

#### Full Email Skeleton

```html
<div style="max-width: 640px; margin: 0 auto; font-family: 'Inter', 'Helvetica Neue', Helvetica, Arial, sans-serif; background-color: #FFFFFF; color: #292524; color-scheme: light only; -webkit-color-scheme: light only;">

  <!-- Header banner (already dark — looks great in both modes) -->
  <div style="background-color: #1C1917; padding: 16px 24px; border-radius: 8px 8px 0 0;">
    <span style="color: #F59E0B; font-size: 13px; font-weight: 600; letter-spacing: 1px; text-transform: uppercase;">Auto Shipper — Progress Report</span><br>
    <span style="color: #A8A29E; font-size: 13px;">NR-{NNN} · {Date}</span>
  </div>

  <!-- Body — explicit background-color on every container -->
  <div style="padding: 24px; background-color: #FFFFFF;">

    <h1 style="font-size: 28px; font-weight: 700; color: #292524; margin: 0 0 12px 0; padding-bottom: 12px; border-bottom: 3px solid #F59E0B;">{Title}</h1>

    <!-- TL;DR callout -->
    <div style="background-color: #FFFBEB; border-left: 4px solid #F59E0B; padding: 16px 20px; border-radius: 4px; margin: 20px 0;">
      <span style="font-size: 12px; font-weight: 600; color: #D97706; text-transform: uppercase; letter-spacing: 0.5px;">TL;DR</span>
      <p style="font-size: 16px; color: #292524; line-height: 1.6; margin: 8px 0 0 0;">{summary text}</p>
    </div>

    <!-- Images: always framed for dark mode -->
    <img src="https://raw.githubusercontent.com/{org}/{repo}/{branch}/docs/nathan-reports/{file}.png"
         alt="{Descriptive fallback text}"
         style="width: 100%; max-width: 600px; height: auto; margin: 16px 0; border-radius: 8px; border: 1px solid #E7E5E4; padding: 4px; background-color: #FFFFFF;">

    <!-- Sections follow with H2/H3, body text, tables, callouts as needed -->
    <!-- IMPORTANT: every element must have explicit inline color and background-color -->

  </div>

  <!-- Footer -->
  <div style="padding: 16px 24px; border-top: 1px solid #E7E5E4; background-color: #FFFFFF;">
    <p style="font-size: 12px; color: #A8A29E; margin: 0;">Commerce Engine · Auto Shipper AI<br>Generated from session work on {date}.</p>
  </div>

</div>
```
