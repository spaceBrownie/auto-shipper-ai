---
name: engineering-post
description: Write technical engineering blog posts from the Auto Shipper AI team's perspective — practitioner-to-practitioner write-ups with real benchmarks, code examples, and actionable takeaways for developers and AI-assisted coders. Use this skill when the user asks to write an engineering post, create a technical write-up, document something for the engineering blog, write a case study, or share learnings from a technical decision. Also trigger when the user says things like "write this up for other engineers" or "document how we solved this."
---

# Engineering Post

Write technical blog posts as the Auto Shipper AI engineering team. The audience is other
engineers — people who build things, not people who buy things.

## Voice

Write like a senior engineer explaining something at a whiteboard to a peer they respect.
First-person plural throughout ("we built", "we measured", "we learned"). No corporate tone,
no marketing language, no hedging. Be direct about what worked, what didn't, and why.

The reader is smart and busy. They want to know: what did you do, why, what happened, and
can I use this? Answer those four questions and get out.

## Research before writing

Before drafting, gather the real data. The credibility of every post depends on specifics —
actual numbers, actual code, actual file paths. Generic posts are worthless.

1. **Read the codebase** for the topic area — understand the actual implementation, not just
   the concept. Pull real code snippets, real class names, real file paths.

2. **Find measurements** — if the conversation contains benchmarks, token counts, timing data,
   or before/after comparisons, use those exact numbers. If not, run the measurements yourself
   where possible (e.g., `wc -c`, token estimates, `time` commands).

3. **Check git history** for context — `git log --oneline` on relevant files to understand
   the evolution. What came before? What was the previous approach?

4. **Use graphify if available** — run `graphify filemap` or `graphify query` to get structural
   context about the code areas being discussed.

5. **Check feature-requests/** for spec/plan context if the topic maps to an FR.

## Post structure

Save output to `docs/engineering-posts/{slug}.md` where slug is a kebab-case derivative
of the title.

Use this template:

```markdown
# {Title}

*Auto Shipper AI Engineering | {YYYY-MM-DD}*

**TL;DR** — {3-4 sentences. What we did, what the result was, why it matters to you.}

---

{Main body — see sections below}

---

## Try it yourself

{Concrete steps the reader can take to apply this in their own codebase. Not "consider
doing X" but "here's the command, here's the config, here's what to expect."}

---

*Tags: {comma-separated, lowercase}*
```

## Main body guidelines

**Lead with the problem.** What was broken, slow, or missing? Use specific numbers.
"Our Phase 1 subagent was making 17 tool calls just to find 5 files" is better than
"file discovery was inefficient."

**Show the approach, not just the result.** Walk through the thinking. What options existed?
Why did you pick this one? What tradeoffs did you accept? Engineers want to understand the
decision space so they can adapt it to their own context.

**Include real code.** Pull actual snippets from the codebase — annotate them if needed but
don't sanitize them into toy examples. Show the real class names, real method signatures,
real config. This is what makes engineering posts credible.

**Show the measurements.** Before/after numbers, benchmark tables, token counts, timing data.
If you ran an eval, show the eval. If you have a comparison table, include it. The bar for
"we measured this" is: would another engineer be able to reproduce a similar measurement?

**Be honest about limitations.** What doesn't this solve? Where does it break down? What
would you do differently next time? This builds trust and helps readers evaluate whether
the approach fits their situation.

**ASCII diagrams** are welcome for data flows, architecture, or before/after comparisons.
Keep them simple — if it needs more than 15 lines, it's too complex for a blog post.

## What makes a good title

Good titles are specific and promise something useful:
- "How We Cut Agent File Discovery From 17 Tool Calls to Zero"
- "AST Knowledge Graphs as Context Injection for AI Coding Agents"
- "The Transaction Pattern That Kept Silently Dropping Our Event Listeners"

Bad titles are vague or hyperbolic:
- "Improving Our Development Workflow" (says nothing)
- "Revolutionary AI-Powered Code Navigation" (marketing)
- "10 Things We Learned About Knowledge Graphs" (listicle bait)

## Length

Target 800-1500 words for the main body. Long enough to be substantive, short enough that
a busy engineer will finish it. If a topic needs more depth, consider splitting into a series.

## What this skill is NOT

- Not a README or internal documentation (those go in `docs/`)
- Not a PM report (that's the nathan-report skill)
- Not a changelog or release notes
- Not a tutorial from scratch — assume the reader has engineering context
