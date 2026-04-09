# How We Cut Agent File Discovery From 17 Tool Calls to Zero

*Auto Shipper AI Engineering | 2026-04-09*

**TL;DR** — Our AI coding agents were spending 30% of their tool calls on `grep` and `glob` just to find which files to read. We built a `filemap` command on top of an AST-extracted knowledge graph that gives agents an instant file map — class names to source files with line numbers — for ~200 tokens and zero LLM cost. Across 6 eval scenarios it achieves 100% recall vs. 24% for the existing graph query, at 3x fewer tokens. The whole thing runs in 110ms with no API calls.

---

## The problem: agents rediscovering your codebase every time

We run a 6-phase feature-request workflow where each phase executes in an isolated subagent. Phase 1 (Discovery) explores the codebase to understand what needs to change. Phase 3 (Planning) reads the affected files to design a solution.

Here's the thing: every subagent starts with zero context about the codebase. So the first thing each one does is burn tool calls finding files:

```
Phase 1 Discovery agent — 17 tool calls:
  ~5 glob/grep to find files          ← this is waste
  ~12 reads to understand the code    ← this is the actual work

Phase 3 Planning agent — 42 tool calls:
  ~12 glob/grep to find files         ← this is waste
  ~30 reads to understand the code    ← this is the actual work
```

That's ~30% of each agent's work just figuring out where things live. On a 2,000-class Kotlin monolith, a search for `CjDropshippingAdapter` means grepping through 745 files before you can start reading the 5 files that matter.

We already had a knowledge graph. We'd run [graphify](https://github.com/safishamsi/graphify) on our `modules/` directory and had a `graph.json` with 2,084 nodes and 1,729 edges extracted via tree-sitter AST parsing. No LLM cost — just deterministic structural extraction.

The graph was sitting right there with every class name, file path, and line number already indexed. We just needed a better way to query it.

## Why the existing query command didn't work

graphify ships a `query` CLI that does BFS/DFS traversal from seed nodes. We tried it:

```bash
graphify query "CjDropshippingAdapter SupplierProductMappingResolver DemandScanJob" --budget 2000
```

The output was 20 lines of this:

```
NODE DemandScanJobTest [src=...DemandScanJobTest.kt loc=L39 community=18]
NODE .`scan run failure status is persisted when exception occurs`() [src=...DemandScanJobTest.kt loc=L291]
NODE .`job disabled returns early without creating scan run`() [src=...DemandScanJobTest.kt loc=L157]
...
```

Three problems:

1. **Test method noise.** Every test method name is a separate node. Names like `.`happy path - adapter returns Success with supplierOrderId`()` eat 50%+ of the token budget before the traversal reaches neighboring production classes.

2. **No cross-file edges.** The AST extraction creates `class → method` edges but not `class → imports` edges. BFS can't traverse from `CjDropshippingAdapter` to `DemandScanJob` because there's no structural edge connecting them.

3. **Fuzzy seed matching.** The query `"demand scan"` matched `DemandScanSmokeService` before `DemandScanJob` because the scoring is pure substring frequency.

The result: **~1,500 tokens spent, 1 out of 5 target files found.** Worse than grep.

## What we built: `graphify filemap`

Instead of traversing the graph, we query it as a lookup index. The `filemap` command takes comma-separated class names, finds matching nodes, and returns a compact file map grouped by source file:

```bash
$ graphify filemap "CjDropshippingAdapter,SupplierProductMappingResolver,DemandScanJob" --methods

modules/fulfillment/src/main/kotlin/.../SupplierProductMappingResolver.kt
  SupplierProductMappingResolver (L8)
    .resolve() (L12)

modules/portfolio/src/main/kotlin/.../DemandScanJob.kt
  DemandScanJob (L23)
    .run() (L38)
    .collectFromSources() (L101)
    .persistCandidate() (L137)
    .createExperiment() (L178)

modules/portfolio/src/main/kotlin/.../CjDropshippingAdapter.kt
  CjDropshippingAdapter (L15)
    .fetch() (L27)
    .mapProduct() (L68)

---
3 classes across 3 files (~255 tokens)
```

Three design decisions:

- **Test files filtered by default.** Production code only unless you pass `--include-tests`. This eliminates the noise problem entirely.
- **No traversal.** Direct node lookup via exact label match (priority 2.0) or substring match (priority 1.0). You know the class names — you don't need BFS.
- **Grouped by file.** Multiple classes in the same file are listed together. This is how engineers think about code — by file, not by node.

The implementation is ~80 lines of Python added to graphify's `__main__.py`. It reads `graph.json` (which tree-sitter already built), filters, groups, and prints. No LLM calls, no network, no dependencies beyond the existing graph.

## The eval: 6 scenarios, 8x improvement

We ran both commands against 6 real feature-request scenarios from our codebase, each with ground-truth file lists. The scenarios ranged from single-module (4 files) to cross-module (5+ files across 3 modules):

| Scenario | `filemap` recall | `query` recall | `filemap` tokens | `query` tokens |
|---|---|---|---|---|
| Pricing (narrow, 4 files) | **4/4** | 1/4 | 126 | 1,096 |
| Order fulfillment (broad, 5 files) | **5/5** | 0/5 | 1,013 | 1,005 |
| Capital + portfolio (complex, 5 files) | **5/5** | 1/5 | 159 | 1,509 |
| Vendor governance (narrow, 4 files) | **4/4** | 1/4 | 135 | 1,159 |
| Compliance (cross-module, 4 files) | **4/4** | 2/4 | 141 | 1,163 |
| Broad keywords (CJ, Supplier) | **4/4** | 1/4 | 773 | 965 |

**Aggregate: 100% recall vs. 24.2% recall, 2.9x fewer tokens.**

Edge cases handled cleanly — misspelled class names find partial matches, nonexistent classes return empty output with exit code 0.

The one weakness: broad keywords like `"Order"` match 32 files (every class with "Order" in the name). The fix is straightforward — use specific class names, not single-word keywords. When you're working from a Linear ticket, you already know the class names.

## The lifecycle: hydrate, enrich, rebuild

We wired filemap into our feature-request workflow at three points:

```
Pre-Phase 1:  graphify filemap "classes from ticket" > FR-dir/filemap.txt
              ↓ inject ~200 tokens into subagent prompt
              
Between phases: re-run with newly discovered classes → enrich

Post-Phase 6: graphify rebuild modules --out graphify-out
              ↓ AST re-parse of changed .kt files (~2 seconds)
              ↓ graph.json updated for next feature
```

The key insight: the filemap call is **free.** Zero LLM tokens, 110ms, ~200 tokens of output. There's no reason to be conservative with it — run it at every phase transition. The cost of carrying 200 extra tokens in a 1M-token context window is 0.02%.

The graph rebuild after a feature ships is also nearly free — tree-sitter re-parses only changed code files in ~2 seconds. No LLM involved. So the next feature request starts with an up-to-date graph.

## Limitations

**No semantic edges.** The AST graph knows `CjDropshippingAdapter` exists and has a `.fetch()` method, but doesn't know it implements `DemandSignalProvider` or that `DemandScanJob` calls it. Running graphify with `--mode deep` would add import/call edges via Claude, but that costs tokens. For our use case — "where are the files?" — structural nodes are enough.

**No code content.** The filemap tells you `CjSupplierOrderAdapter` is at line 12 of a specific file. It doesn't tell you there's a hardcoded `fromCountryCode=CN` on line 49. You still need to read the file. Filemap eliminates the *finding*; the *reading* is still required.

**Stale if you don't rebuild.** If you add a new class and don't run `graphify rebuild`, the filemap won't find it. We mitigate this by rebuilding after every feature PR.

---

## Try it yourself

**1. Install graphify and build your graph:**
```bash
pip install git+https://github.com/safishamsi/graphify.git
graphify .   # or graphify ./src, ./modules, etc.
```

**2. Query it with filemap:**
```bash
# Exact class names (best precision)
graphify filemap "UserService,AuthController,TokenProvider"

# Keywords (broader, may have noise)
graphify filemap "Auth,Token,Session"

# With methods
graphify filemap "UserService" --methods

# Include test files
graphify filemap "UserService" --include-tests
```

**3. Inject into your agent prompts:**
```bash
FILEMAP=$(graphify filemap "ClassA,ClassB,ClassC")
# Include $FILEMAP in your subagent's system prompt or task description
```

**4. Keep the graph fresh after code changes:**
```bash
graphify rebuild ./src --out graphify-out
```

The filemap command is currently in our fork. The upstream graphify project is at [github.com/safishamsi/graphify](https://github.com/safishamsi/graphify) — the `graph.json` it produces is compatible; you just need the filemap command added to `__main__.py`.

---

*Tags: knowledge-graphs, ai-coding-agents, ast, developer-tooling, claude-code, graphify, token-optimization*
