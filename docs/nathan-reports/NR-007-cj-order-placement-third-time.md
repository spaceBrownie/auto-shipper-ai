# NR-007: CJ Order Placement — Third Time's the Charm

**Date:** 2026-03-29
**Linear:** RAT-27
**Status:** Completed

---

## TL;DR

The system can now automatically place purchase orders with CJ Dropshipping the moment a customer buys from our Shopify store — no human in the loop. This is the feature that makes the zero-capital model real: customer pays, system places the supplier order, supplier ships directly to customer. It took three attempts across two workflow versions to get right, but the third attempt using the redesigned v3 workflow shipped clean on the first review cycle with 42 real tests and zero data integrity bugs.

## The Autonomous Order Pipeline

Here's what the system does now, end-to-end, without any human involvement:

![Autonomous Order Flow](assets/order-flow.png)

Every step labeled "auto" happens in milliseconds. The customer's payment on Shopify funds the CJ order — zero working capital required.

## The Story of Three Attempts

This feature is the economic engine of the entire system. Without it, every order requires someone to manually log into CJ Dropshipping and place a purchase order. With it, the pipeline runs autonomously: customer buys on Shopify → system confirms the order → system places the CJ order → CJ ships to the customer. The customer's payment funds the supplier order. No inventory, no warehouse, no working capital.

It should have been straightforward. It wasn't.

**Attempt 1 (PR #37)** shipped with a hardcoded quantity of 1. Customer orders 3 widgets, CJ gets told to ship 1. The retry logic was also broken — the system caught its own errors before the retry mechanism could see them, so transient API failures silently died instead of retrying. Both bugs would have been invisible until real orders started failing.

**Attempt 2 (PR #39)** tried to fix those issues but introduced something worse. Nine out of ten shipping address fields had a data corruption bug: when Shopify sends an empty phone number, the system stored the literal text "null" — the word, not the absence of a value. CJ would receive "null" as the customer's phone number, "null" as their apartment number, "null" as their province. The kicker: 65 automated quality checks were generated. Not a single one caught this. Fifty of them were the testing equivalent of a home inspector stamping PASS without entering the house.

Both PRs were thrown away entirely. No code from either attempt exists in the system today.

![Three Attempts Comparison](assets/three-attempts.png)

**Attempt 3 (this one)** used the completely redesigned workflow that came out of the NR-006 skill evaluation. The workflow now has six phases instead of four, with a dedicated test specification phase that defines every test before any code is written. A strategy engine recommends how many parallel work streams to use and whether to move fast or be deliberate. For this feature — given the two prior failures and the financial sensitivity — it recommended deliberative mode with three parallel work streams.

The result: 46 files changed, 42 real quality checks (zero fake ones), all passing — including full end-to-end tests against the actual database. The Unblocked code reviewer caught one additional scenario that all 42 checks missed (more on that below), which was fixed and verified before merging. One review cycle, clean merge.

## What the System Can Do Now

- **Automatic supplier ordering**: When a Shopify customer completes a purchase, the system places the corresponding order with CJ Dropshipping within seconds — no manual intervention
- **Shipping address flow-through**: Customer's shipping details flow from Shopify all the way to CJ's warehouse with full data integrity — every field is protected against the "null" corruption bug that killed Attempt 2
- **Graceful failure handling**: If CJ rejects an order (out of stock, invalid address, API down), the order is marked as failed with the specific reason — no silent data loss, no orders stuck in limbo
- **Duplicate protection**: Network retries and system restarts don't produce duplicate CJ orders — the system recognizes it already placed the order and skips
- **Multi-supplier ready**: CJ is the first supplier, but the system is structured so adding Printful, Printify, or any other dropship supplier means plugging in a new connection, not rebuilding

## Why This Matters

This was the last piece needed for the autonomous order pipeline. The path from "customer clicks Buy" to "supplier ships product" is now fully automated:

1. Customer buys on Shopify ✓ (RAT-26, already done)
2. System detects the sale and creates the order ✓ (FR-023, already done)
3. **System places supplier order with CJ** ✓ (this feature)
4. CJ ships, tracking number comes back → RAT-28 (next)

Without step 3, the system was a fancy order inbox. With it, the system is an autonomous commerce engine — at least for the ordering half. Tracking and fulfillment sync (RAT-28) is the remaining gap before end-to-end autonomy.

The three-attempt saga also validated something important about the workflow redesign. NR-006 documented how Attempts 1 and 2 failed because the old workflow let the system skip quality checks. The new workflow forced deliberative mode, a test specification before coding, and external code review. It worked. The process gaps we found this time (test spec not fully enforced, review loop not automated) are improvement items, not showstoppers — a very different class of problem than "65 tests, zero real coverage."

## The Bug That Tests Missed

The Unblocked code reviewer flagged a scenario none of the 42 checks covered: what happens if a CJ order fails, and then something retries the placement?

The system checks "has a supplier order already been placed?" by looking for a CJ order ID on the internal order. But a *failed* order has no CJ order ID — the placement never succeeded. So the duplicate-protection check passes, the system tries CJ again, and then tries to mark the order as "failed" when it's *already* failed. The system's status rules don't allow going from "failed" to "failed" — it crashes.

This wouldn't happen today (nothing retries failed orders yet), but it's exactly the kind of time bomb that detonates six months later during a 2am incident. The fix was a five-line guard: only attempt supplier placement on confirmed orders. Two regression checks lock it down.

The takeaway: automated checks cover the scenarios you think of. External review covers the ones you don't. Both are essential.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Shopify webhook listener | ● Done | RAT-26 / FR-023 — orders flow in |
| CJ supplier order placement | ● Done | RAT-27 / FR-025 — this feature |
| Tracking number ingestion | ● Not Started | RAT-28 — CJ sends tracking back, sync to Shopify |
| Supplier product mapping | ● Done | Table exists, needs population per SKU |
| Feature workflow v3 | ● Done | First real run, 4 improvement items identified |

## What's Next

- **RAT-28: Tracking number ingestion** — CJ sends tracking info via webhook or polling. The system needs to capture it and push it to Shopify so customers see shipping updates. This completes the end-to-end autonomous loop.
- **Supplier product mapping population** — The system knows *how* to link our products to CJ's catalog, but the links need to be set up per product. This happens when the first real product is selected for launch.
- **Workflow v3 improvements** — Four concrete items from PM-018: enforce the test specification as a binding contract, add automated review comment detection, verify database state before tests, document strategy engine overrides.

## Session Notes: The Parallel Agent Strategy

The strategy engine recommended chunking 28 tasks into groups of 7. Instead, we partitioned by *file ownership* — a different approach that turned out to prevent conflicts entirely:

![Parallel Agent Strategy](assets/agent-strategy.png)

The key insight: agents that create new files can never conflict with each other (they're writing to files that don't exist yet). Agents that modify existing files can conflict — but only if two agents touch the same file. By assigning each agent exclusive ownership of specific files, all 9 parallel sessions (3 rounds × 3 agents) completed with zero coordination issues. This "new files first, modifications second, tests third" pattern is worth keeping.


Additional wins from this session:
- Fixed 5 pre-existing data corruption bugs in the Shopify order processing as a bonus — same "null" text issue from Attempt 2, but in fields that existed before this feature. These would have been silent until real orders with missing phone numbers or empty apartment fields hit the system.
- Full test suite now runs against a real database — not simulated fakes. This caught a problem where the new automatic confirmation changed order status in a way that broke an existing test. Simpler testing would have missed it entirely.
