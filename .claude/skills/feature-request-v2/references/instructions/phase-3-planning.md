# Phase 3: Implementation Planning

**Goal:** Design technical solution and create task breakdown.

## Steps

1. **Read spec.md** for requirements.

2. **Use /unblock as needed** — query `unblocked_context_engine` for the key entities being
   modified (classes, services, modules). Look for: team conventions for this area, patterns
   used in similar implementations, and architectural decisions that would constrain the design.

3. **Check filemap (if provided):**
   If the orchestrator included a `## File Map` section in your prompt, use it to skip
   glob/grep for file discovery. The filemap gives you class names → source files → line
   numbers. Go straight to reading the files you need instead of searching for them.

4. **Design technical solution:**
   - Follow DDD/hexagonal architecture
   - Identify affected layers: handler, domain, proxy, security, config, common
   - Consult layer-specific AGENTS.md files for constraints

4. **Write implementation-plan.md** — validate before writing:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 3 --action write --path "feature-requests/FR-{NNN}-{name}/implementation-plan.md"
   ```

   Required sections:
   - **Technical Design** — architecture overview, design decisions
   - **Architecture Decisions** — why this approach over alternatives
   - **Layer-by-Layer Implementation** — detailed design for each layer
   - **Task Breakdown** — GitHub-style checkboxes grouped by layer
     Keep task breakdown explicit (`- [ ] ...` per task) so meta-controller
     can infer workload state accurately.
   - **Testing Strategy** — unit, integration, e2e tests
   - **Rollout Plan** — deployment steps, rollback procedure

5. **Gut-check with /unblock** — verify the technical approach hasn't been tried and rejected,
   and that architecture decisions align with team patterns.

6. **Validate deliverables:**
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 3 --check-deliverables --feature-dir "feature-requests/FR-{NNN}-{name}"
   ```

## Deliverable

`feature-requests/FR-{NNN}-{feature-name}/implementation-plan.md`

## Permissions

- **Read:** Everything
- **Write:** `feature-requests/FR-*/implementation-plan.md` only
- **Bash:** ls, cat, grep, git log, git status (read-only)
