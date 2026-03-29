# Phase 6: Review-Fix Loop

**Goal:** Create a pull request and iterate until review is clean and CI is green.

## Steps

1. **Create pull request:**
   ```bash
   gh pr create --title "FR-{NNN}: {feature-name}" --body "$(cat <<'EOF'
   ## Summary
   <generated from summary.md>

   ## Test Plan
   <generated from test-spec.md>

   ## Spec
   See `feature-requests/FR-{NNN}-{feature-name}/spec.md`
   EOF
   )"
   ```

2. **Use /unblock to review PR** — query `unblocked_context_engine` to check for patterns
   or issues the automated review might flag. This is a proven v1 pattern.

3. **Check PR status:**
   ```bash
   gh pr checks
   gh pr view --comments
   ```

4. **Assess review comments and CI failures:**
   - Read each review comment and CI failure log
   - Categorize: code correctness, style/convention, test gaps, CI configuration

5. **Fix issues** — validate permissions before writing fixes:
   ```bash
   python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 6 --action write --path "{file}"
   ```
   Run tests locally before pushing:
   ```bash
   ./gradlew test
   ```

6. **Push fixes and re-check:**
   ```bash
   git add -A && git commit -m "address review feedback" && git push
   gh pr checks
   ```

7. **Repeat until clean** — continue steps 3-6 until:
   - All CI checks pass (green)
   - All review comments resolved
   - No new review comments raised

## Exit Criteria

- CI checks: all green
- Review comments: all resolved
- No manual approval needed — the loop exits automatically when criteria are met

## Deliverable

PR with green CI and no unresolved review comments.

## Permissions

- **Read:** Everything
- **Write:** Same as Phase 5 (code files, test files, build configs, docs)
- **Bash:** Full development workflow + PR interaction (gh pr create, gh pr view, git push)
- **Forbidden:** rm -rf, git push --force, git reset --hard
