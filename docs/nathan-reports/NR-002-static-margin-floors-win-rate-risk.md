# NR-002: Static Margin Floors May Be Limiting Our Addressable Market

**Date:** 2026-03-21
**Linear:** N/A (investigative analysis)
**Status:** Investigation Complete — Decision Needed

---

## TL;DR

We investigated how the 50% gross / 30% net margin floors are enforced across the system. They're real, hard requirements — applied identically to every SKU regardless of category, channel, or volume. This is intentionally conservative and protects capital, but it structurally excludes product categories that could be profitable at thinner margins and blocks common go-to-market strategies like market-entry pricing. This is a strategic trade-off worth revisiting.

## What Changed

Nothing was changed — this is an analysis of the current system design. Here's what we confirmed:

- **The margin floors are enforced at three independent checkpoints**, creating a triple-lock:
  1. **Pre-listing stress test** — every SKU must survive 2x shipping, +15% CAC, +10% supplier cost, 5% refund rate, and 2% chargeback rate, and *still* clear 50% gross / 30% net. Fail = auto-terminated, no override possible.
  2. **Dynamic pricing engine** — if a cost change (shipping spike, CAC increase, vendor price hike) would push a live SKU below the 30% net floor, the system automatically pauses or terminates it.
  3. **Capital protection monitor** — if a SKU's net margin dips below 30% for 7+ consecutive days, auto-shutdown fires.

- **The floors are configurable** (not baked into the code as magic numbers), but they apply uniformly to every SKU in the system. There's no way to set different thresholds by product category, sales channel, or volume tier.

- **The 30% net floor is enforced at the type level** — the system literally cannot construct a launch-ready SKU with a net margin below 30%. It's not a check that can be bypassed; it's a structural constraint baked into how the data flows.

## Why This Matters

The current design optimizes for **capital preservation over market breadth**. That's the right default for a zero-capital system in Phase 1 — we can't afford to learn expensive lessons about thin-margin products when there's no cash cushion.

But it creates a meaningful constraint on win rate:

- **Entire product categories are excluded.** Commodity goods, consumables, accessories, and most electronics can't clear a 50% gross margin even with optimal sourcing. These categories represent a large share of e-commerce demand.
- **Market-entry pricing is impossible.** The system can't temporarily accept thinner margins to build demand, establish reviews, or win organic rankings — even if the math works over a 90-day horizon.
- **High-volume/low-margin plays are off the table.** A product doing $50K/month at 25% net margin ($12.5K/month profit) gets killed, while a product doing $2K/month at 35% net margin ($700/month profit) stays alive. The system optimizes for margin percentage, not absolute profit.
- **No category intelligence.** A 35% net margin on a digital product is mediocre; the same 35% on consumer electronics is exceptional. The system treats them identically.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Margin floor enforcement | Done | Triple-lock across stress test, pricing, and capital modules |
| Per-SKU or per-category overrides | Not Built | No schema, no service layer, no UI support |
| Configuration flexibility | Partial | Thresholds are configurable globally, but not per-SKU |
| Impact on addressable market | Needs Analysis | No data yet on how many product opportunities fail solely due to margin floors |

## What's Next

This is a **strategic decision, not a technical one.** The system can be extended to support dynamic margin floors — the architecture is modular enough. But the question is whether we *should*, and under what conditions.

- **Option A: Keep the current floors** — accept the narrower addressable market as a feature, not a bug. We only play in high-margin categories. Simplest, safest, and aligned with the "durable net profit" mandate.

- **Option B: Category-aware margin policies** — define different floor thresholds per product category (e.g., 40% gross / 25% net for consumables, 55% gross / 35% net for digital). Requires a new policy layer and a decision framework for which categories qualify.

- **Option C: Graduated floors based on demand signals** — allow SKUs with proven demand (high conversion, low return rate, strong signal scores) to operate at tighter margins. New SKUs still face the full 50/30 gate; only graduates get flexibility. Most sophisticated, highest win rate potential.

- **Option D: Time-boxed exceptions** — allow sub-floor margins for a defined launch window (e.g., 30 days), with automatic kill if margins don't improve to floor levels. Enables market-entry pricing without permanent risk exposure.

## Risks & Decisions Needed

- **Decision: Is the current win rate acceptable?** → **Ask:** Nathan, do we have a sense of how many product opportunities the system is evaluating vs. how many clear the stress test? If the pass rate is, say, 10%, that might be fine for Phase 1. If it's 2%, we're leaving money on the table.

- **Decision: Which option (A/B/C/D) aligns with Phase 1 priorities?** → **Ask:** Should we scope a feature request for dynamic margin floors, or is this a Phase 2 concern? If Phase 2, we should document the decision so we don't relitigate it.

- **Risk: The 30% floor is baked into a type-level constraint.** The stress-tested margin type will reject any value below 30% regardless of configuration. If we go with Option B, C, or D, that type constraint needs to become configurable too — it's not just a config file change.

## Session Notes

- The investigation confirmed the system is well-designed for its stated goal (capital preservation). This isn't a bug — it's a deliberate architectural choice that has trade-offs worth understanding.
- All three enforcement points (stress test, pricing engine, capital monitor) read from external configuration, so the global thresholds can be adjusted without code changes. Per-SKU flexibility would require new development.
