# Phase 6: Review-Fix Loop

**Goal:** Create a pull request and iterate until review is clean and CI is green.
This phase runs an automated polling loop — do not wait for the user to report comments.

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

3. **Enter the automated review-fix polling loop:**

   After PR creation, poll for review comments automatically. Do NOT wait for the user
   to report comments — the loop is self-driven.

   ```
   LOOP:
     a. Wait 60 seconds (allow review bots and CI to run)
     b. Poll for new comments and CI status:
        gh api repos/{owner}/{repo}/pulls/{pr_number}/comments
        gh api repos/{owner}/{repo}/pulls/{pr_number}/reviews
        gh pr checks {pr_number}
     c. IF new comments found:
        - Read and categorize each comment (code correctness, style, test gap, CI config)
        - Fix the issues (validate permissions, run tests locally)
        - Commit and push fixes
        - GOTO LOOP
     d. IF CI checks failed:
        - Read failure logs
        - Fix the issues
        - Commit and push
        - GOTO LOOP
     e. IF PR approved AND CI green AND no unresolved comments:
        - EXIT LOOP — Phase 6 complete
     f. IF no new activity after 3 consecutive polls:
        - Report status to user: "PR is waiting for review. No new comments in 3 minutes."
        - Continue polling (the loop does not exit on silence — only on approval)
   ```

   **Key behaviors:**
   - **Self-driving:** The orchestrator polls and fixes without user intervention
   - **Comment dedup:** Track which comment IDs have been processed to avoid re-fixing
   - **Batch fixes:** If multiple comments arrive in one poll, fix them all in one commit
   - **Build before push:** Always run `./gradlew test` before pushing fixes
   - **Escalate to user** only if a comment requires a design decision (not a code fix)

4. **For each review comment, assess and fix:**
   - Read the comment in full, including the diff context
   - Check if the issue is also flagged by CLAUDE.md constraints or prior postmortems
   - Fix the code, update tests if needed
   - Validate:
     ```bash
     python3 .claude/skills/feature-request-v2/scripts/validate-phase.py --phase 6 --action write --path "{file}"
     ./gradlew test
     ```

5. **Push fixes:**
   ```bash
   git add {specific files} && git commit -m "fix: address review — {brief description}" && git push
   ```

## Exit Criteria

- CI checks: all green
- PR approved (at least one approval, no changes-requested)
- All review comments addressed (no unresolved threads)
- The loop exits automatically when all three criteria are met

## Deliverable

PR with green CI, approval, and no unresolved review comments.

## Permissions

- **Read:** Everything
- **Write:** Same as Phase 5 (code files, test files, build configs, docs)
- **Bash:** Full development workflow + PR interaction (gh pr create, gh pr view, git push, gh api)
- **Forbidden:** rm -rf, git push --force, git reset --hard
