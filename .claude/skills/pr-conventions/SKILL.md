---
name: pr-conventions
description: >
  Enforces project git conventions for branch naming, commit messages, and PR creation.
  Use this skill whenever creating a git branch, writing commit messages, or creating a
  pull request. Also use when the user says "create a PR", "push this", "let's branch",
  "open a pull request", "commit this", or any variation. This skill prevents common
  mistakes like using Linear's default branch name (dennygx/...) or writing PRs without
  the required sections. Even if you think you know the conventions, consult this skill
  — it's the single source of truth.
---

# PR & Git Conventions

This project has specific conventions for branch names, commit messages, and pull request
structure. These conventions exist because consistent naming makes `git log`, branch lists,
and PR dashboards scannable at a glance — and because the project tracks work through
Linear tickets (RAT-*, FR-*) that should be traceable from the git history.

## Branch Naming

**Format:** `{type}/{ticket}-{slug}`

| Type | When to use |
|------|-------------|
| `feat/` | New features, feature requests (FR-*), new capabilities |
| `bugfix/` | Bug fixes, incident fixes |
| `docs/` | Documentation-only changes |
| `chore/` | Maintenance, refactoring, CI changes, dependency updates |

**Examples:**
```
feat/FR-024-codebase-quality-audit
feat/RAT-26-shopify-order-webhook
bugfix/frontend-console-errors
docs/workflow-improvement-plan
chore/current-state-cleanup
```

**Rules:**
- Slug is kebab-case, lowercase
- Include the ticket ID when one exists (RAT-*, FR-*)
- If no ticket, use a descriptive slug: `bugfix/null-pointer-in-auth-flow`
- **Never** use a username prefix like `dennygx/` — Linear suggests these as default branch
  names, but they are not the project convention. Always strip the username prefix and
  replace it with the appropriate type prefix.

**Deriving from Linear:** When a Linear ticket provides a suggested branch name like
`dennygx/rat-21-one-time-codebase-audit-listener-patterns-api-contracts`, transform it:
1. Drop the username prefix (`dennygx/`)
2. Add the type prefix (`feat/`)
3. Shorten the slug if it's excessively long — keep the ticket ID and enough context
   to understand the branch at a glance

`dennygx/rat-21-one-time-codebase-audit-listener-patterns-api-contracts`
→ `feat/RAT-21-codebase-quality-audit`

## Commit Messages

**Format:** `{type}: {description}`

The type prefix matches the nature of the change, not the branch type:

| Prefix | Use for |
|--------|---------|
| `feat:` | New functionality, new capabilities |
| `fix:` | Bug fixes, corrections |
| `docs:` | Documentation changes |
| `test:` | Test-only changes (new tests, test infrastructure) |
| `chore:` | Build, CI, dependency, or tooling changes |
| `refactor:` | Code restructuring without behavior change |

**Rules:**
- First line: `{type}: {concise description}` — under 72 characters
- Optional body after blank line for context (the *why*, not the *what*)
- Reference tickets/PMs when relevant: `CLAUDE.md constraint #6, sourced from PM-001`
- End with co-author line when AI-assisted:
  ```
  Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
  ```

**Examples from this project:**
```
feat: FR-023 Shopify order webhook listener (RAT-26)
fix: BR-1 — VendorSlaBreachRefunder cross-module listener transaction boundary
docs: PM-016 — feature-request-v2 dry run learnings
test: BR-4 — ArchUnit Rule 4 for cross-module @EventListener detection
fix: handle concurrent duplicate webhook PK violation + add empty-secret tests
chore: add FR-023 webhook test count gates + search all modules
```

**Multi-line commit messages** — always use a HEREDOC to preserve formatting:
```bash
git commit -m "$(cat <<'EOF'
feat: FR-024 codebase-quality-audit — spec, plan, test manifest

Discovery, specification, planning, and test-first gate artifacts
for RAT-21 one-time codebase audit.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Pull Request Template

Every PR must have these sections. Use a HEREDOC to pass the body:

```bash
gh pr create --title "{type}: {short title}" --body "$(cat <<'EOF'
## Summary
<1-5 bullet points describing what changed and why>

## Test Plan
<Checklist of testing performed — use [x] for completed, [ ] for pending>

## Source
<Linear ticket, postmortem references, spec path — whatever links this PR to its origin>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**PR Title:** Same convention as commit messages — `{type}: {description}`, under 72 characters.
For feature requests, use `FR-{NNN}: {feature-name}` as the title.

**Summary section:** Lead with the *why*. Bullet points, not paragraphs. Each bullet
should be scannable — a reviewer skimming the PR list should understand the change from
the summary alone.

**Test Plan section:** Concrete and specific. Not "tests were added" but:
```markdown
- [x] All 16 new tests pass (9 blank-credential, 2 URL-encoding, 4 contract, 1 ArchUnit)
- [x] `./gradlew build` — BUILD SUCCESSFUL, zero failures
- [ ] Verify application starts in all profiles
```

**Source section:** Traceability back to the work item. Include:
- Linear ticket: `RAT-21`, `FR-024`
- Postmortem references if applicable: `PM-001, PM-003`
- Spec path if from feature-request workflow: `feature-requests/FR-024-.../spec.md`

## Quick Reference

When creating a branch + PR in one flow:

```bash
# 1. Create branch
git checkout -b feat/RAT-21-codebase-quality-audit

# 2. Make commits (atomic, per-concern)
git commit -m "$(cat <<'EOF'
fix: BR-1 — VendorSlaBreachRefunder cross-module listener

Replace @EventListener with @TransactionalEventListener(AFTER_COMMIT).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"

# 3. Push and create PR
git push -u origin feat/RAT-21-codebase-quality-audit
gh pr create --title "FR-024: codebase-quality-audit" --body "$(cat <<'EOF'
## Summary
- One-time compliance audit for CLAUDE.md constraints

## Test Plan
- [x] `./gradlew build` — all tests pass

## Source
- **Linear**: RAT-21

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
