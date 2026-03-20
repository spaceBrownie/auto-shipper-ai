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
   - **To:** Nathan's email (ask the user if not known; save to memory once provided)
   - **Body:** The full report content. Convert markdown to clean HTML for email readability — headers, bullet points, tables. Keep the same structure but render it for an email client, not a code editor.
2. Tell the user the draft is ready in Gmail for final review and send
3. The file in `docs/nathan-reports/` stays as a local reference copy

If Gmail MCP is not available, tell the user to forward the file manually and note which email to send to.
