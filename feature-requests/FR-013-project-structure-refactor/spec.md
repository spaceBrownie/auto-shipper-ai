# FR-013: Project Structure Refactor

## Problem Statement

The repository root conflates Gradle build tooling with code modules. Nine subproject
directories (`app`, `shared`, `catalog`, `pricing`, `vendor`, `fulfillment`, `capital`,
`compliance`, `portfolio`) sit directly alongside `settings.gradle.kts`, `build.gradle.kts`,
`gradlew`, `gradle/`, and project-level config files. This makes it harder to distinguish
build infrastructure from application code and creates friction when onboarding contributors
or scanning the project at a glance.

## Business Requirements

1. All 9 subproject module directories must be moved into a `modules/` subdirectory at the
   repository root.
2. Gradle task paths and inter-module `project(":name")` references must remain unchanged —
   no Gradle script inside any module may require editing due to this move.
3. The full Git commit history for every moved file must be preserved and traversable via
   `git log --follow`.
4. The build (`./gradlew build`) and test suite (`./gradlew test`) must remain green after
   the restructure with no regressions.
5. No application code behaviour changes. This is a pure structural (filesystem + build
   config) change.

## Success Criteria

- `./gradlew build` exits 0 after the restructure.
- `./gradlew test` exits 0 with the same test results as before.
- `./gradlew :catalog:test` (and equivalent task paths for all 9 modules) works without
  change.
- `git log --follow modules/catalog/src/main/kotlin/com/autoshipper/catalog/domain/Sku.kt`
  returns the full history back to the original commit.
- The repository root contains no module directories; `modules/` contains exactly the 9
  modules.
- Existing feature-request `implementation-plan.md` files (FR-006 through FR-012) carry a
  path-update header note so future implementors use the correct post-refactor paths.

## Non-Functional Requirements

- **History preservation:** All file moves use `git mv`, not `cp` + `rm`, so `--follow`
  traversal works across the rename boundary.
- **Zero downtime:** No running service is disrupted; this is a build-system-only change.
- **Gradle compatibility:** The `projectDir` remapping technique used in
  `settings.gradle.kts` is compatible with Gradle 8.x (the project's current Gradle
  version).
- **CLAUDE.md accuracy:** Any module path references in `CLAUDE.md` are updated to reflect
  the new `modules/` prefix.

## Dependencies

- None. This is a self-contained structural change with no dependency on other feature
  requests and no effect on application runtime behaviour.
- FR-006 through FR-012 have `implementation-plan.md` files that reference source paths;
  those plans will receive a path-update header note (not a rewrite) so they remain
  accurate for future implementors.
