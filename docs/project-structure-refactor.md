# Project Structure Refactor Plan

## Problem

The root directory conflates four distinct concerns at the same level:

- **Gradle build system** — `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradle/`
- **Code modules** — `app/`, `shared/`, `catalog/`, `pricing/`, `vendor/`, `fulfillment/`, `capital/`, `compliance/`, `portfolio/`
- **Project management layer** — `feature-requests/`
- **Documentation** — `docs/`, `README.md`, `CLAUDE.md`

---

## Part 1: Gradle Restructure

### Target layout

```
auto-shipper-ai/
├── build.gradle.kts
├── settings.gradle.kts       ← updated to remap projectDir
├── gradlew / gradlew.bat / gradle/ / gradle.properties
├── CLAUDE.md / README.md / .gitignore / .env.example / .claude/
│
├── modules/                  ← NEW: all Gradle subprojects move here
│   ├── app/
│   ├── shared/
│   ├── catalog/
│   ├── pricing/
│   ├── vendor/
│   ├── fulfillment/
│   ├── capital/
│   ├── compliance/
│   └── portfolio/
│
├── docs/                     ← already exists, no change
│
└── feature-requests/         ← stays at root (project management, not code)
```

### Keeping the build intact: `projectDir` remapping

Gradle supports redirecting a subproject's filesystem location independently of its project path. Update `settings.gradle.kts`:

```kotlin
rootProject.name = "auto-shipper-ai"

include(
    "shared",
    "catalog",
    "pricing",
    "vendor",
    "fulfillment",
    "capital",
    "compliance",
    "portfolio",
    "app"
)

// Remap all subprojects to the modules/ subdirectory
rootProject.children.forEach { project ->
    project.projectDir = File(rootDir, "modules/${project.name}")
}
```

**What this preserves without any other changes:**

| Concern | Status |
|---|---|
| `./gradlew :catalog:test` | Unchanged — Gradle task paths use project name, not directory |
| `implementation(project(":shared"))` in `build.gradle.kts` | Unchanged — project reference is by name |
| `./gradlew bootRun`, `./gradlew build` | Unchanged |
| Flyway migration paths inside `app/` | Unchanged — relative to module source root |
| IDE project imports | Unchanged — Gradle project graph is identical |

**What changes physically:**
- The 9 module directories move from root → `modules/`
- `settings.gradle.kts` gains the `projectDir` block (4 lines)
- Nothing else

### Migration steps

1. Create `modules/` directory
2. `git mv` each of the 9 module directories into `modules/` — preserves full per-file `git log --follow` history
3. Add `projectDir` remapping block to `settings.gradle.kts`
4. Run `./gradlew build` and `./gradlew test` to verify — must be green

> Use `git mv` (not plain `mv`). Plain `mv` would make `git log` lose file history at the old path. `git mv` keeps it navigable via `git log --follow`.

---

## Part 2: FR Audit Trail Strategy

### The problem

FR-006 through FR-012 have `implementation-plan.md` files with file-path references like:

```
catalog/src/main/kotlin/com/autoshipper/catalog/domain/Sku.kt
```

After the restructure these paths become:

```
modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/Sku.kt
```

Simply moving directories without updating these files would leave all planned FRs referencing non-existent paths.

### Solution: Create FR-013 (project-structure-refactor)

Track this work as a proper FR. This is the right choice because:

- The audit trail is **enriched**, not broken — the log shows exactly when the structure changed and why
- FR-013's `implementation-plan.md` explicitly includes updating path refs in FR-006–FR-012 as a task
- Future implementors reading any planned FR see a header note that paths were updated by FR-013
- The planned FRs retain their full semantic content — only physical paths change

### FR-013 implementation plan tasks

1. Create `modules/` directory
2. `git mv` all 9 subproject dirs into `modules/`
3. Update `settings.gradle.kts` with `projectDir` remapping
4. `./gradlew build && ./gradlew test` — must be green
5. Add a path-update header note to each of FR-006 through FR-012 `implementation-plan.md`:
   ```
   > **Path update (FR-013):** All source paths below use the post-refactor
   > `modules/` prefix, e.g. `modules/catalog/src/...` instead of `catalog/src/...`
   ```
6. Update `CLAUDE.md` build command examples to reflect new paths
7. Write `summary.md` — confirm zero functional changes, all tests passing

### What NOT to do

- **Do not move `feature-requests/`** — it is project management, not code, and moving it would break `.claude/skills/` script path assumptions and FR workflow conventions
- **Do not move `docs/`** — already cleanly separated, no value in restructuring
- **Do not batch-replace paths across all FRs** — add the header note instead. This preserves the original intent of each plan while flagging the path change explicitly and traceably

---

## Files touched

| File | Change |
|---|---|
| `settings.gradle.kts` | Add `projectDir` remapping block |
| `modules/` (new dir) | Physical home for all 9 subprojects |
| `feature-requests/FR-006` → `FR-012` `implementation-plan.md` | Add path-update header note |
| `CLAUDE.md` | Update example paths in build commands section |
| `feature-requests/FR-013-project-structure-refactor/spec.md` | New FR spec |
| `feature-requests/FR-013-project-structure-refactor/implementation-plan.md` | New FR plan |

---

## Verification

```bash
# After moving modules + updating settings.gradle.kts:
./gradlew build          # must be green
./gradlew test           # must be green
./gradlew :catalog:test  # task path unchanged
./gradlew bootRun        # app starts

# Confirm git history preserved:
git log --follow modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/Sku.kt
# Should show full history back to original commit
```
