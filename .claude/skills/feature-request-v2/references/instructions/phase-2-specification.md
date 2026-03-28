# Phase 2: Specification

**Goal:** Document feature requirements in spec.md.

## Steps

1. **Get next FR number:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --next-fr-number
   ```

2. **Use /unblock as needed** — query `unblocked_context_engine` with the feature area and
   key entities. Look for: prior specs for similar features, business requirements discussions,
   and stakeholder constraints that may not be in the codebase.

3. **Create feature directory:**
   ```bash
   mkdir -p feature-requests/FR-{NNN}-{feature-name}
   ```

4. **Write spec.md** — validate before writing:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --action write --path "feature-requests/FR-{NNN}-{name}/spec.md"
   ```

   Required sections:
   - **Problem Statement** — what problem are we solving?
   - **Business Requirements** — what must the feature do?
   - **Success Criteria** — how do we know it's done?
   - **Non-Functional Requirements** — performance, security, scalability
   - **Dependencies** — what does this depend on?

   Do NOT include: Implementation Details, Technical Design, Code Changes.

5. **Gut-check with /unblock** — verify key assumptions: Do the business requirements align
   with prior team decisions? Does the scope conflict with in-progress work?

6. **Validate deliverables:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
   ```

## Deliverable

`feature-requests/FR-{NNN}-{feature-name}/spec.md`

## Permissions

- **Read:** Everything
- **Write:** `feature-requests/FR-*/spec.md` only
- **Bash:** mkdir (FR directory), ls, cat, grep
