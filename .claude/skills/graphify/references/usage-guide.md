Graphify — Setup & Usage Guide

  How it's installed

  - Source: Your fork (spaceBrownie/graphify), pinned to commit 3d53287
  - Location: .graphify-venv/ (isolated Python venv, not system Python)
  - Skill: .claude/skills/graphify/skill.md
  - No PreToolUse hook, git hooks, or CLAUDE.md modifications

  Commands

  Full pipeline — builds the knowledge graph from scratch:
  /graphify                              # current directory
  /graphify ./modules                    # specific path
  /graphify ./modules --mode deep        # aggressive inference, richer INFERRED edges
  /graphify ./modules --no-viz           # skip HTML visualization, just report + JSON

  Incremental updates:
  /graphify ./modules --update           # re-extract only new/changed files (SHA256 cache)
  /graphify ./modules --cluster-only     # rerun community detection on existing graph

  Query an existing graph:
  /graphify query "how does the cost gate work"         # BFS traversal (broad)
  /graphify query "order fulfillment flow" --dfs        # DFS traversal (deep trace)
  /graphify query "pricing signals" --budget 1500       # cap answer tokens
  /graphify path "CostGateService" "StressTestEngine"   # shortest path between concepts
  /graphify explain "KillWindowMonitor"                 # plain-language node explanation

  Ingest external content:
  /graphify add <url>                          # fetch URL → save to ./raw → update graph
  /graphify add <url> --author "Name"          # tag author
  /graphify add <url> --contributor "Name"     # tag who added it

  Export formats:
  /graphify ./modules --svg                              # graph.svg (embeds in GitHub/Notion)
  /graphify ./modules --graphml                          # graph.graphml (Gephi, yEd)
  /graphify ./modules --neo4j                            # cypher.txt for Neo4j import
  /graphify ./modules --neo4j-push bolt://localhost:7687 # push directly to Neo4j
  /graphify ./modules --obsidian                         # Obsidian vault with index.md
  /graphify ./modules --obsidian --obsidian-dir ~/vaults/auto-shipper
  /graphify ./modules --mcp                              # start MCP stdio server
  /graphify ./modules --watch                            # auto-rebuild on file changes (code only, no LLM)

  What it produces

  All output goes to graphify-out/ (gitignored):

  ┌─────────────────┬──────────────────────────────────────────────────────────────────────┐
  │      File       │                              What it is                              │
  ├─────────────────┼──────────────────────────────────────────────────────────────────────┤
  │ graph.json      │ Machine-readable knowledge graph (nodes, edges, communities)         │
  ├─────────────────┼──────────────────────────────────────────────────────────────────────┤
  │ GRAPH_REPORT.md │ Plain-language audit: god nodes, communities, surprising connections │
  ├─────────────────┼──────────────────────────────────────────────────────────────────────┤
  │ graph.html      │ Interactive vis.js visualization (default, always generated)         │
  ├─────────────────┼──────────────────────────────────────────────────────────────────────┤
  │ graph.svg       │ Static SVG (only with --svg)                                         │
  ├─────────────────┼──────────────────────────────────────────────────────────────────────┤
  │ wiki/           │ Obsidian vault (only with --obsidian)                                │
  └─────────────────┴──────────────────────────────────────────────────────────────────────┘

  How extraction works

  1. Code files (.kt, .java, .py, .ts, etc.) — parsed locally via tree-sitter AST. No LLM, no network calls. Extracts classes, functions,
  imports, call graphs, docstrings.
  2. Docs (.md, .txt, .rst) — processed by Claude subagents for semantic extraction. Costs tokens.
  3. Papers (.pdf) — same as docs, requires pip install graphifyy[pdf] in the venv.
  4. Images (.png, .jpg, .webp) — Claude vision processing. Costs tokens.

  Edge confidence tags

  Every relationship in the graph carries a classification:
  - EXTRACTED (confidence 1.0) — found directly in source
  - INFERRED (0.0–1.0) — reasonable inference with confidence score
  - AMBIGUOUS — flagged for manual review

  Supported languages

  19 via tree-sitter: Python, JS, TS, Go, Rust, Java, C, C++, Ruby, C#, Kotlin, Scala, PHP, Swift, Lua, Zig, PowerShell, Elixir, Objective-C

  Kotlin support means it can parse your entire modules/ backend.

  What it won't do (by our config)

  - Won't auto-install from PyPI — errors out with reinstall instructions if venv is missing
  - Won't intercept your Glob/Grep calls (no PreToolUse hook)
  - Won't auto-rebuild on commits (no git hooks)
  - Won't modify CLAUDE.md or settings.json