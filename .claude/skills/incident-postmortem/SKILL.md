# Incident Post-Mortem

```yaml
name: incident-postmortem
description: >
  Generate a structured incident post-mortem document after a significant bug is discovered and fixed
  during a coding session. This skill analyzes the current conversation history to reconstruct the
  incident timeline, root cause, fix, and lessons learned — then writes a markdown report.
  Use this skill whenever: the user says "post-mortem", "incident report", "write up what happened",
  "document this bug", "postmortem", or after a significant debugging session where a non-trivial
  bug was found and resolved. Even if the user just says something like "let's document that" after
  fixing a tough bug, this skill applies.
```

## Overview

After a significant bug is discovered and fixed during a session, this skill reconstructs what happened from the conversation history and produces a structured post-mortem document. The goal is to capture institutional knowledge — not just *what* broke, but *why* it broke and how to prevent similar issues.

## How It Works

You have access to the full conversation history. Use it. The best post-mortems are specific — they name files, quote error messages, and trace the exact chain of events. Avoid vague summaries like "a bug was found and fixed." Instead, tell the story of what happened.

## Step 1: Identify the Incident

Scan the conversation for the debugging arc. Look for:
- The initial symptom (error message, unexpected behavior, test failure)
- Investigation steps (files read, hypotheses tested, dead ends)
- The root cause discovery moment
- The fix and its verification

If there were multiple bugs fixed in the session, ask the user which one(s) to document. If it's clearly just one incident, proceed without asking.

## Step 2: Determine the Next PM Number

```bash
ls docs/postmortems/PM-*.md 2>/dev/null | sort -V | tail -1
```

If no post-mortems exist yet, start at PM-001. Otherwise increment from the highest.

Create the directory if needed:
```bash
mkdir -p docs/postmortems
```

## Step 3: Generate a Slug

Create a short kebab-case slug from the incident (e.g., `pricing-404-on-listed-skus`, `null-pointer-in-auth-flow`). Keep it under 50 characters and descriptive enough that someone browsing the directory understands what happened.

## Step 4: Write the Post-Mortem

Save to `docs/postmortems/PM-{NNN}-{slug}.md` using this structure:

```markdown
# PM-{NNN}: {Title}

**Date:** {YYYY-MM-DD}
**Severity:** {Critical | High | Medium | Low}
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

{2-3 sentence plain-English summary. What broke, what the impact was, and how it was fixed.
Write this for someone who has 30 seconds to understand the incident.}

## Timeline

| Time | Event |
|------|-------|
| ... | Initial symptom observed: {what happened} |
| ... | Investigation: {key steps taken} |
| ... | Root cause identified: {what was found} |
| ... | Fix applied: {what changed} |
| ... | Verified: {how it was confirmed working} |

## Symptom

{What the user or system observed. Include exact error messages, HTTP status codes,
stack traces, or unexpected outputs. Quote directly from the session where possible.}

## Root Cause

{Technical explanation of why the bug occurred. Be specific — name the file, the line,
the incorrect assumption, the missing piece. This is the most important section.
Someone reading this should understand the failure mechanism well enough to recognize
similar issues in the future.

Include relevant code snippets to make the root cause concrete. Show the problematic
code or configuration — a short snippet is worth more than a paragraph of description.}

## Fix Applied

{What was changed and why. Reference specific files and the nature of the change.
Include before/after code snippets where they clarify the fix — especially for
subtle changes like annotation additions or interface extractions.}

### Files Changed
- `path/to/file.kt` — {what changed and why}

## Impact

{What was affected. Could users hit this bug? Was data corrupted? Was it limited to
local dev? Be honest about the blast radius.}

## Lessons Learned

### What went well
- {Things that helped identify or fix the issue quickly}

### What could be improved
- {Process gaps, missing tests, architectural issues that allowed this bug}

## Prevention

{Concrete follow-up actions to prevent recurrence. These should be specific and actionable,
not generic advice like "write more tests." Think: what structural change would make this
class of bug impossible or immediately caught?

Consider whether any prevention item could be automated — a CI check, a pre-commit hook,
a small reusable skill, or a linter rule. If so, note it. The best prevention is the kind
that runs automatically and catches the problem before a human has to remember.}

- [ ] {Action item 1}
- [ ] {Action item 2}
```

## Writing Guidelines

**Severity levels:**
- **Critical** — Production data loss, security breach, system down
- **High** — Core feature broken, workaround difficult or impossible
- **Medium** — Feature degraded, workaround exists, or caught before production
- **Low** — Minor issue, cosmetic, or only affects edge cases

**Timeline:** Use relative timestamps if exact times aren't available (e.g., "Session start", "After ~15 min investigation"). The timeline should read like a narrative — someone should be able to follow the debugging journey.

**Tone:** Blameless. Post-mortems document systems failures, not people failures. Use "the system" or "the code" as subjects, not "I" or "the developer." Focus on the chain of events and contributing factors.

**Root cause depth:** Apply the "5 whys" explicitly. Structure the root cause as a chain of causation:
1. **Why** did pricing return 404? → No `SkuPriceEntity` existed.
2. **Why** was no entity created? → `setInitialPrice()` was never called.
3. **Why** was it never called? → No event listener bridged the state change to pricing.
4. **Why** was there no listener? → The modules were built in separate FRs without an integration contract.
5. **Why** wasn't that caught? → No integration test covers the cross-module flow.

Don't stop at "the function wasn't called." Trace back to the systemic gap that allowed the bug to exist.

## Step 5: Present to User

After writing the file, show the user:
1. The file path
2. The summary section
3. Ask if they want to adjust severity, add context, or modify any section

## Example Trigger Phrases

- "write a post-mortem for this"
- "document what just happened"
- "create an incident report"
- "let's do a post-mortem"
- "PM this bug"
