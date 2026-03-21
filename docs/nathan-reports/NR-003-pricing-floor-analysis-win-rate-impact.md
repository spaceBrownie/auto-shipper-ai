# NR-003: Static Margin Floors — Capital Protection vs. Win Rate

**Date:** 2026-03-21
**Linear:** N/A (investigative analysis)
**Status:** Decision Needed

---

## TL;DR

We investigated how the 50% gross / 30% net margin floors are enforced across the system. They're hard requirements applied uniformly to every SKU — no per-category or per-product overrides exist. The floors are enforced at three independent checkpoints (stress test pre-listing, dynamic pricing post-listing, capital protection monitoring), creating a triple-lock that makes it structurally impossible to list or keep alive a product below these thresholds. This is the right default for a zero-capital system, but it likely reduces our addressable market by excluding viable low-margin/high-volume categories and blocking market-entry pricing strategies.

## What We Found

We traced the margin floor enforcement end-to-end through the system:

- **Gate 1: Stress test (pre-listing)** — before any SKU can be listed, it must survive all five shock scenarios (2x shipping, +15% CAC, +10% supplier cost, 5% refund rate, 2% chargeback rate) and *still* clear 50% gross / 30% net. The 30% net floor isn't just a check — the system physically cannot construct a launch-ready SKU below that threshold. It's a structural constraint, not a policy flag.

- **Gate 2: Dynamic pricing (post-listing)** — if any cost signal changes (carrier rate hike, CAC drift, vendor price increase) and the recalculated margin would drop below 30% net, the pricing engine either adjusts the price upward or triggers a pause/terminate signal. No human in the loop.

- **Gate 3: Capital protection (monitoring)** — if a live SKU's actual net margin stays below 30% for 7+ consecutive days, the auto-shutdown fires and the Shopify listing is pulled. This is the same kill rule that now auto-pauses Shopify listings (shipped in RAT-13 this week).

- **All three gates read from configuration** — the 50/30 thresholds live in the application config, not in code. But they're global: one number applies to every SKU in the system. There's no mechanism for category-level, channel-level, or per-SKU overrides.

## Why This Matters

The triple-lock is doing exactly what it was designed to do: protect capital in a zero-inventory, zero-capital system where one bad product can wipe out the reserve. That's the right posture for Phase 1.

But it creates trade-offs worth quantifying:

- **Entire product categories are structurally excluded.** Commodity goods, consumables, phone accessories, most electronics — these can't clear a 50% gross margin even with optimal sourcing. These categories represent a large share of e-commerce search demand.

- **Market-entry pricing is impossible.** The system can't temporarily accept thinner margins to build reviews, establish organic rankings, or win early customers — even if the economics work over a 90-day horizon. Every SKU must be profitable from day one at stressed margins.

- **Absolute profit is ignored.** A product generating $50K/month at 25% net ($12.5K/month profit) gets killed, while a product doing $2K/month at 35% net ($700/month profit) stays alive. The system optimizes for margin percentage, not absolute dollars.

- **No category context.** A 35% net margin on a digital product is mediocre. The same 35% on consumer electronics is exceptional. The system treats them identically.

## Options for Consideration

These aren't recommendations — they're the design space. Each has different risk/complexity profiles:

| Option | Description | Win Rate Impact | Risk | Complexity |
|--------|-------------|----------------|------|------------|
| **A: Keep current floors** | Accept narrower market as a feature. Only play in high-margin categories. | None (baseline) | Lowest | None |
| **B: Category-aware policies** | Different floors by product category (e.g., 40% gross / 25% net for consumables) | Medium | Medium — need category-specific risk models | Medium |
| **C: Graduated floors on demand proof** | New SKUs face full 50/30 gate. SKUs with proven demand (high conversion, low returns) earn tighter floors. | High | Medium — bad signal = bad graduation | High |
| **D: Time-boxed exceptions** | Allow sub-floor margins for a defined launch window (e.g., 30 days), auto-kill if margins don't recover. | Medium | Medium — cash exposure during the window | Low-Medium |

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Margin floor enforcement | Working as designed | Triple-lock across stress test, pricing, and capital modules |
| Per-SKU / per-category overrides | Not Built | No schema, no service layer, no UI support |
| Configuration flexibility | Partial | Thresholds are configurable globally but not per-SKU |
| Win rate data | Unknown | No tracking yet on how many opportunities fail solely due to margin floors |
| Autonomous pipeline (overall) | 6 of 8 stages | Demand → Cost Gate → Stress Test → Pricing → Shopify Listing → Monitoring all live |

## What's Next

This is a strategic decision, not a technical one. The system can be extended — the architecture is modular enough. The question is whether we should, and when.

- **Immediate (no code change):** Start tracking stress test pass/fail rates. If we see the data — how many SKUs get evaluated vs. how many clear the gate — we'll know whether this is a theoretical concern or a real bottleneck. This is low-effort and high-signal.

- **If win rate is a problem:** Option D (time-boxed exceptions) is the lowest-complexity path that unlocks market-entry pricing without permanent risk exposure. Option B (category-aware) gives the most targeted improvement but requires defining category risk profiles.

- **Regardless of decision:** The structural constraint in the stress-tested margin type would need to become configurable if we pursue B, C, or D. It currently hard-rejects anything below 30% at the type level, which means even a config change wouldn't be enough — it's a code change too.

## Risks & Decisions Needed

- **Do we have pass rate data?** → **Ask:** Nathan, do we know (or can we instrument) how many product opportunities the demand scan evaluates vs. how many survive the stress test? If the pass rate is 15%+, Option A is probably fine for Phase 1. If it's under 5%, we're potentially leaving significant profit on the table.

- **Phase 1 or Phase 2?** → **Ask:** Should we scope a feature request for dynamic margin floors now, or park it as a Phase 2 enhancement? If Phase 2, let's document the decision so we don't relitigate.

## Session Notes

- This investigation was prompted by the question of whether uniform margin floors are hurting our product win rate. The answer: probably yes, but we don't have the data to say by how much.
- The system is well-designed for its stated goal (capital preservation). This is a trade-off analysis, not a bug report.
- The most actionable next step is instrumenting pass/fail rates at the stress test gate — that turns this from a theoretical discussion into a data-driven decision.
