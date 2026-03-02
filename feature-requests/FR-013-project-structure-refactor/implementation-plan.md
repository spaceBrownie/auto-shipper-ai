# FR-013: Project Structure Refactor — Implementation Plan

## Technical Design

Move all 9 Gradle subproject directories from the repository root into a `modules/`
subdirectory. Gradle's `projectDir` override in `settings.gradle.kts` remaps each
subproject's directory without changing any task path (`:catalog:test` stays
`:catalog:test`). File history is preserved by using `git mv` rather than `cp` + `rm`.

### Architecture Decisions

| Decision | Choice | Rationale |
|---|---|---|
| How to move files | `git mv` | Preserves `git log --follow` history across the rename boundary |
| Gradle remapping | `projectDir` override in `settings.gradle.kts` | Zero changes to module-level `build.gradle.kts` files; task paths unchanged |
| `frontend/` location | Stays at root | `frontend/` is not a Gradle subproject; no remapping needed |
| Other root files | Unchanged | `build.gradle.kts`, `gradle/`, `gradlew`, `CLAUDE.md`, `docker-compose.yml`, etc. stay at root |

### projectDir Remapping Block

Add the following to `settings.gradle.kts` immediately after the `include(...)` block:

```kotlin
rootProject.children.forEach { project ->
    project.projectDir = File(rootDir, "modules/${project.name}")
}
```

This is the only change required to Gradle configuration. No module-level build files need
editing.

---

## Layer-by-Layer Implementation

### Build Configuration Layer

`settings.gradle.kts` — add the 3-line `projectDir` remapping block after the `include()`
call. This is the only Gradle file that needs changing.

### Filesystem Layer

Create `modules/` and `git mv` each of the 9 subprojects into it:

| Source | Destination |
|---|---|
| `app/` | `modules/app/` |
| `shared/` | `modules/shared/` |
| `catalog/` | `modules/catalog/` |
| `pricing/` | `modules/pricing/` |
| `vendor/` | `modules/vendor/` |
| `fulfillment/` | `modules/fulfillment/` |
| `capital/` | `modules/capital/` |
| `compliance/` | `modules/compliance/` |
| `portfolio/` | `modules/portfolio/` |

### Documentation Layer

- `CLAUDE.md` — inspect for any hardcoded module paths and update to `modules/<name>/...` if found.
- FR-006 through FR-012 `implementation-plan.md` — prepend path-update header note.
- FR-012 (`frontend-dashboard`) — tailored note: `frontend/` stays at root and FR-012's
  own paths are unaffected, but the backend modules it calls have moved to `modules/`.

---

## Task Breakdown

### Build Configuration
- [x] Validate write permission for `settings.gradle.kts`
- [x] Add `projectDir` remapping block to `settings.gradle.kts` after `include(...)` call

### Filesystem
- [x] Create `modules/` directory
- [x] `git mv app/ modules/app/`
- [x] `git mv shared/ modules/shared/`
- [x] `git mv catalog/ modules/catalog/`
- [x] `git mv pricing/ modules/pricing/`
- [x] `git mv vendor/ modules/vendor/`
- [x] `git mv fulfillment/ modules/fulfillment/`
- [x] `git mv capital/ modules/capital/`
- [x] `git mv compliance/ modules/compliance/`
- [x] `git mv portfolio/ modules/portfolio/`

### Verification
- [x] Run `./gradlew build` — must exit 0
- [x] Run `./gradlew test` — must exit 0 with no regressions
- [x] Run `./gradlew :catalog:test` — task path must work unchanged
- [x] Verify `git log --follow` shows history through the rename for at least one module file

### Documentation Updates
- [x] Inspect `CLAUDE.md` for module path references; update any found to `modules/<name>/...`
- [x] Prepend path-update header to `feature-requests/FR-006-pricing-engine/implementation-plan.md`
- [x] Prepend path-update header to `feature-requests/FR-007-vendor-governance/implementation-plan.md`
- [x] Prepend path-update header to `feature-requests/FR-008-fulfillment-orchestration/implementation-plan.md`
- [x] Prepend path-update header to `feature-requests/FR-009-capital-protection/implementation-plan.md`
- [x] Prepend path-update header to `feature-requests/FR-010-portfolio-orchestration/implementation-plan.md`
- [x] Prepend path-update header to `feature-requests/FR-011-compliance-guards/implementation-plan.md`
- [x] Prepend path-update header (tailored) to `feature-requests/FR-012-frontend-dashboard/implementation-plan.md`

### Completion
- [x] Create `summary.md`

---

## Testing Strategy

- **Build gate:** `./gradlew build` — verifies compilation and packaging.
- **Unit/integration gate:** `./gradlew test` — verifies no regressions in application logic.
- **Task-path spot check:** `./gradlew :catalog:test` — confirms Gradle task addressing is unchanged.
- **History check:** `git log --follow modules/catalog/src/...` on one representative file — confirms rename boundary is traversable.

There are no new unit tests to write; this is a structural change and the existing test
suite is the regression gate.

---

## Rollout Plan

1. Execute all filesystem moves in a single commit (all `git mv` operations staged together).
2. Update `settings.gradle.kts` in the same commit.
3. Run the verification gates.
4. Update documentation (CLAUDE.md + FR-006–FR-012 header notes) in a follow-up commit or
   the same commit, depending on preference.
5. No deployment steps required — this change does not affect any running service.
6. **Rollback:** `git revert` of the commit(s) is sufficient.
