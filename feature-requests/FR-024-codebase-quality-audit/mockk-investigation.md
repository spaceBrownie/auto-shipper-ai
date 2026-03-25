# mockk Investigation: Kotlin Inline Value Class Mocking

**FR-024 — Codebase Quality Audit**
**Date:** 2026-03-25
**Status:** Investigation complete, gradual migration recommended

---

## 1. Problem Statement

Mockito cannot mock or capture Kotlin `@JvmInline value class` parameters. When a method signature uses an inline value class (e.g., `SkuId`), Mockito's `any()` and `capture()` return `null` at the JVM level, which crashes when Kotlin tries to unbox the inline class wrapper.

This was first documented in **PM-008** (FR-011 Vibe Coding Quality Gaps, 2026-03-14) and led to behavioral tests being stripped entirely rather than finding a working approach. The problem recurs across all modules that use `SkuId`, `OrderId`, or other inline value class parameters in service interfaces.

## 2. Reproduction

### The inline value class

```kotlin
// modules/shared/src/main/kotlin/com/autoshipper/shared/identity/SkuId.kt
@JvmInline
value class SkuId(val value: UUID) {
    companion object {
        fun new(): SkuId = SkuId(UUID.randomUUID())
        fun of(value: String): SkuId = SkuId(UUID.fromString(value))
    }
    override fun toString(): String = value.toString()
}
```

### What fails

```kotlin
// Mockito returns null for any<SkuId>() — crashes at inline class unboxing
whenever(skuService.transition(any<SkuId>(), eq(SkuState.Listed))).thenReturn(...)
// → NPE when Kotlin tries to unbox the inline class
```

```kotlin
// ArgumentCaptor has the same problem
val captor = argumentCaptor<SkuId>()
verify(skuService).transition(captor.capture(), eq(SkuState.Listed))
// → NPE during capture
```

### Root cause

At the JVM level, `@JvmInline value class SkuId(val value: UUID)` is erased to `UUID`. The Kotlin compiler generates a mangled method name (e.g., `transition-WZ4Q5Ns(UUID, SkuState)`) to avoid signature collisions. Mockito operates at the JVM bytecode level and does not understand this mangling:

1. `any<SkuId>()` returns `null` (Mockito's default for reference types)
2. Kotlin's generated code tries to call `SkuId.value` on the return value
3. NPE because `null.value` is invalid

### Current workarounds in codebase

The codebase uses two patterns to work around this:

**Pattern 1: Use `any<UUID>()` on the underlying value** (e.g., `MarginSweepSkuProcessorTest`)
```kotlin
// modules/capital/src/test/kotlin/.../MarginSweepSkuProcessorTest.kt line 58:
// "to avoid Mockito issues with @JvmInline value class SkuId parameters"
// Uses real objects instead of mocking SkuId parameters
```

**Pattern 2: Avoid mocking the method entirely** — construct real objects and call methods directly with `UUID` parameters, bypassing the inline class. This is the `OrderEventListenerTest` pattern referenced in PM-008.

Both workarounds are fragile and require developers to remember the limitation.

## 3. mockk Analysis

[mockk](https://mockk.io/) is a Kotlin-native mocking framework that understands inline/value classes at the compiler level.

### How mockk handles inline value classes

mockk intercepts at the Kotlin level rather than the JVM bytecode level, so it correctly handles the name-mangled methods:

```kotlin
// This works with mockk — no NPE, no workarounds needed
val skuService = mockk<SkuService>()
every { skuService.transition(any(), any()) } returns someResult

// Capture also works
val slot = slot<SkuId>()
every { skuService.transition(capture(slot), any()) } returns someResult
// slot.captured is a valid SkuId
```

### mockk version and compatibility

- Latest stable: **1.13.16** (as of March 2026)
- Supports: Kotlin 1.9+, JUnit 5, Spring Boot 3.x
- Coroutine support: `coEvery`, `coVerify` for suspend functions
- Spring integration: `@MockkBean` via `com.ninja-squad:springmockk`

### Key API mappings

| Mockito | mockk |
|---|---|
| `@Mock` | `mockk<T>()` |
| `@InjectMocks` | Manual construction |
| `whenever(x).thenReturn(y)` | `every { x } returns y` |
| `whenever(x).thenThrow(e)` | `every { x } throws e` |
| `verify(mock).method(args)` | `verify { mock.method(args) }` |
| `any()` | `any()` |
| `eq(v)` | `eq(v)` |
| `argumentCaptor<T>()` | `slot<T>()` |
| `doNothing().whenever(x)` | `every { x } just Runs` |
| `verifyNoMoreInteractions(m)` | `confirmVerified(m)` |

## 4. Migration Effort

### Scope

- **52 test files** with Mockito imports across 8 modules
- **536 Mockito import lines** total
- **0 test files** currently using mockk

### Per-file changes required

1. Replace imports: `org.mockito.*` / `org.mockito.kotlin.*` with `io.mockk.*`
2. Replace `@Mock lateinit var x: T` with `private val x = mockk<T>()`
3. Replace `@InjectMocks` with manual construction (already the pattern in some files)
4. Replace `whenever(x).thenReturn(y)` with `every { x } returns y`
5. Replace `verify(mock).method(args)` with `verify { mock.method(args) }`
6. Replace `argumentCaptor<T>()` / `capture()` with `slot<T>()` / `capture(slot)`
7. Remove `@ExtendWith(MockitoExtension::class)` (mockk does not need it)

### Module-by-module file counts

| Module | Test files with Mockito |
|---|---|
| `fulfillment` | 15 |
| `portfolio` | 10 |
| `capital` | 6 |
| `catalog` | 4 |
| `vendor` | 3 |
| `compliance` | 1 |
| `pricing` | 3 |
| `app` (integration) | 2 (may not need migration — integration tests use real beans) |

### Build configuration changes

Add to each module's `build.gradle.kts`:
```kotlin
testImplementation("io.mockk:mockk:1.13.16")
```

For Spring integration tests (if replacing `@MockBean`):
```kotlin
testImplementation("com.ninja-squad:springmockk:4.0.2")
```

### Estimated effort

- **Per file:** 15-30 minutes (mechanical transformation, mostly search-and-replace)
- **Full migration:** 2-3 days for all 52 files
- **Risk:** Low — test-only changes, no production code affected
- **Verification:** Each migrated file can be verified independently by running its tests

## 5. Recommendation: Gradual Migration

**Do NOT do a big-bang migration.** Instead, adopt a gradual coexistence strategy:

### Immediate actions

1. **Add mockk dependency** to all module `build.gradle.kts` files (test-only)
2. **New test files** should use mockk by default
3. **Document the convention** in module-level AGENTS.md files

### Opportunistic migration

When a test file is touched for other reasons (bug fix, new feature, refactor):
1. Migrate that file from Mockito to mockk
2. Verify the file's tests pass
3. Remove Mockito import if it was the last Mockito user in that module's dependencies

### Priority migration targets

These files should be migrated first because they contain the inline value class workarounds:

1. `modules/capital/src/test/kotlin/.../MarginSweepSkuProcessorTest.kt` — explicit workaround comment on line 58
2. `modules/capital/src/test/kotlin/.../ShutdownRuleEngineTest.kt` — same module, likely same workaround
3. Any test file where behavioral assertions were stripped due to Mockito limitations (PM-008 references)

### Coexistence

Mockito and mockk can coexist in the same project (different files). They use different JVM agents, so avoid mixing them in the same test file. The Gradle dependency for both can be declared simultaneously:

```kotlin
testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")  // existing
testImplementation("io.mockk:mockk:1.13.16")                   // new
```

### Exit criteria

Migration is complete when:
- All 52 test files use mockk
- `org.mockito` dependency is removed from all `build.gradle.kts` files
- No inline value class workaround comments remain in the codebase

---

## References

- [PM-008: FR-011 Vibe Coding Quality Gaps](../postmortems/PM-008-vibe-coding-compliance-quality-gaps.md) — documents the original Mockito/inline class failure
- [PM-010: Structured vs Vibe Coding Comparison](../postmortems/PM-010-structured-vs-vibe-coding-comparison.md) — compares approaches
- [mockk documentation](https://mockk.io/)
- [mockk GitHub](https://github.com/mockk/mockk)
- [Spring mockk integration](https://github.com/Ninja-Squad/springmockk)
