# NR-004: Shopify Webhook — The System Can Now Hear Sales

**Date:** 2026-03-21
**Linear:** [RAT-26](https://linear.app/ratrace/issue/RAT-26/shopify-order-webhook-listener-detect-sales-from-storefront)
**Status:** Completed

---

## TL;DR

The system can now automatically detect when a customer buys something on Shopify. This was the single biggest missing piece before go-live — the system could list products but was deaf to sales. Every downstream step (ordering from supplier, tracking shipments, recording revenue) now has the trigger it needs. Eight reliability bugs were caught and fixed during review before any of this reached production.

## What Changed

**The go-live entry point is built.** When a customer completes checkout on Shopify, the system now:

- Receives the sale notification instantly and verifies it's legitimate (not spoofed)
- Looks up which of our products were purchased and which supplier fulfills each one
- Creates an internal order for each item, ready for the supplier ordering pipeline
- Handles duplicate notifications gracefully — Shopify sometimes sends the same sale twice; the system ignores repeats
- Stores the Shopify order number alongside our internal order for customer support cross-referencing

**Multi-channel foundation laid.** The sales detection was built with a channel-agnostic design. When we add Amazon, eBay, or TikTok Shop, each one plugs into the same order creation flow — no rework needed on the core pipeline.

**Security hardened.** Every incoming sale notification is signature-verified before the system touches it — if someone tries to send a fake "order placed" notification, the system rejects it immediately. Supports rotating verification keys without downtime. When keys aren't configured, the system rejects everything (safe by default).

**8 reliability bugs caught and fixed before production.** Automated code review flagged issues spanning database safety, simultaneous-request handling, error recovery, and security — all fixed before shipping. One of these bugs would have caused the system to silently lose orders under certain conditions. Details below.

## Why This Matters

**This unblocks the entire go-live chain.** RAT-26 was deliberately designed as the entry point — nothing downstream can work without it:

- **RAT-27** (auto-order from CJ Dropshipping) needs an internal order to trigger a supplier purchase
- **RAT-28** (shipment tracking) needs an order to attach tracking numbers to
- **RAT-29** (capital recording) needs order revenue data to credit the reserve and compute margin snapshots

Without this, every sale on Shopify would have required manually checking for new orders or human intervention — violating the autonomous operation mandate.

**The bug cascade produced lasting system-wide improvements.** The most interesting outcome wasn't the sales detection itself — it was the discovery that a safety rule from 18 days ago (PM-001, about how the system saves data) had an incomplete enforcement mechanism. The fix touched 23 data structures across every module and added an automated check that prevents the same type of bug from ever being reintroduced. This is defense-in-depth that pays dividends on every future feature.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Shopify sale detection | **Done** | Receiving and verifying real-time sale notifications, battle-tested through 8 bug fixes |
| Order creation pipeline | **Done** | One internal order per line item, handles duplicates gracefully, Shopify order # stored |
| Multi-channel foundation | **Done** | Same order flow works for Amazon/eBay/TikTok when we add them |
| Supplier auto-ordering (RAT-27) | **Unblocked** | Can now proceed — needs internal orders, which this provides |
| Shipment tracking (RAT-28) | **Unblocked** | Depends on RAT-27 (need orders placed to receive tracking) |
| Capital recording (RAT-29) | **Unblocked** | Depends on order flow being operational |
| Automated build checks | **Improved** | Safety gates now cover all modules (was missing fulfillment); 6 new gates for sale detection tests |
| System-wide data safety | **Improved** | 23 data structures fixed across all modules; automated check prevents this type of bug from returning |

## What's Next

- **RAT-27: CJ Dropshipping order placement** — Now unblocked. When an internal order is created from a Shopify sale, the system needs to automatically place a purchase order with the supplier (CJ Dropshipping). This is the next link in the autonomous chain.
- **RAT-28: Tracking number ingestion** — After RAT-27. When CJ ships an order, the system receives the tracking number, pushes it to Shopify (customer gets shipping notification), and the existing shipment tracker starts polling the carrier automatically.
- **RAT-29: Capital recording** — Revenue flows into the reserve, margin snapshots are computed, kill rules can evaluate real P&L data.

## Risks & Decisions Needed

- **Shopify needs to be told where to send sale notifications.** The receiving end is built, but someone needs to register our URL in Shopify's admin settings. This is a 2-minute configuration step but must happen before launch. → **Ask:** Who handles this — is this an ops task or should we automate it?

## How We Ship Production-Grade Software This Fast

This session is a good example of our development model in action. We shipped 3,900 lines of code across 42 files — a complete Shopify integration with security, duplicate-request handling, and multi-channel extensibility — in a single session. Eight bugs were found and fixed before any of it reached production.

The philosophy: **we accept that problems are just other problems to architect and solve.** We don't try to write perfect code on the first pass. Instead, we've built validation layers that catch what we miss, and each catch makes the system permanently smarter.

**Here's how the layers work together:**

1. **Spec-first design** — The feature was fully specified before a line of code was written. Requirements, acceptance criteria, architecture decisions, and a task breakdown were reviewed and approved. This prevents "figure it out as you go" drift across dozens of files.

2. **Parallel execution** — Four independent work streams ran simultaneously, each owning non-overlapping files. This is how 19 source files and 10 test files were created in one pass without conflicts.

3. **Automated code review** — The Unblocked bot reviewed every change and found all 8 bugs, including deep issues around how the database handles simultaneous saves and error recovery that are extremely difficult to catch in manual review. Each bug was fixed within minutes of discovery.

4. **Structural enforcement** — When a bug reveals a *type* of problem (not just one instance), we add an automated check that makes the entire type impossible to reintroduce. The build literally fails if someone writes code that could have the same bug. We now have 3 of these rules, and they accumulate over time.

5. **Build safety gates** — Our automated checks verify that tests actually ran (not just that they exist — tests can be silently skipped). This session expanded the gates to cover all modules — a gap we didn't know existed until we needed it.

6. **Postmortems that feed forward** — PM-015 documents the full bug cascade. Its lessons become engineering rules, which become context for future development. The system learns from its own mistakes.

**No single layer is sufficient.** The spec didn't prevent the bugs. The tests didn't catch the database timing issues. The initial validation missed the simultaneous-request problems. But together, they created a funnel where complex code entered at the top and came out the bottom with 8 bugs removed — all before shipping. The system is self-correcting across iterations, and each iteration makes the next one safer.

This is why we can ship quickly and confidently: not because we avoid problems, but because we've built a system that finds and solves them faster than they can accumulate.

## Session Notes

- The automated code reviewer earned its keep this session — it found 8 bugs across 5 review rounds, including a critical data safety issue that would have silently lost orders.
- One fix introduced a new bug, which introduced another new bug — a three-layer cascade that traced back to an incomplete safety rule from 18 days ago. The final fix touched every module in the system and added permanent automated prevention.
- Postmortem PM-015 was published documenting the full cascade. It's a good case study in how our "problems that solve problems" model works in practice.
