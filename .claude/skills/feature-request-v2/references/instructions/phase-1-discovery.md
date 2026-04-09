# Phase 1: Discovery (Read-Only)

**Goal:** Understand the codebase and generate a valid feature name.

## Steps

1. **Load workflow configuration:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action info
   ```

2. **Use /unblock as needed** — query `unblocked_context_engine` with the Linear ticket ID
   and feature area. Look for: related PRs, prior attempts, Slack discussions, rejected
   approaches, and existing conventions relevant to this work.

3. **Check filemap (if provided):**
   If the orchestrator included a `## File Map` section in your prompt, use it to skip
   glob/grep for file discovery. The filemap gives you class names → source files → line
   numbers. Go straight to reading the files you need instead of searching for them.

4. **Explore codebase (read-only):**
   - Before reading any file, validate:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action read --path "{file}"
     ```
   - Use allowed bash commands (ls, cat, grep, git log, git status)
   - Before bash commands, validate:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 1 --action bash --command "{cmd}"
     ```

4. **Generate feature name:**
   - Create kebab-case name (e.g., "jwt-refresh-tokens")
   - Validate name:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --feature-name "{name}"
     ```
   - If validation fails, generate a new name

5. **Gut-check with /unblock** — verify the proposed feature name and scope don't conflict
   with in-progress work or existing features.

## Deliverable

Valid feature name (kebab-case, 3-50 chars, lowercase alphanumeric with hyphens).

## Permissions

- **Read:** Source code, docs, config files
- **Write:** None
- **Bash:** ls, cat, grep, git log, git status (read-only)
