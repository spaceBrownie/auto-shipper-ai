# NR-011: Strategic Architecture Review — Gaps, Priorities, and the Path to First Purchase

**Date:** 2026-04-18
**Linear:** RAT-44 · RAT-48 · RAT-49 · RAT-50 · RAT-51 · RAT-52 · RAT-53 · RAT-54
**Status:** In Progress — board updated, dev store test path cleared

---

## TL;DR

We ran a full strategic audit of the system against seven key business questions — signals, resilience, platform exposure, defensibility, guardrails, scalability, and strategic direction. The audit revealed that the defensive layer (margin protection, vendor monitoring, auto-refunds, kill rules) is solid and structurally enforced. The gaps are in the offensive layer: YouTube and Reddit demand signals are built but not live, one manual step remains between product discovery and a live Shopify listing, and the marketing engine hasn't started. We captured everything on Linear, clarified the exact four configuration values needed to run a safe dev store test with dummy cards, and confirmed no code changes are required to get there.

---

## What Changed

**Board reprioritized:**
- Observability (RAT-44) promoted to **Urgent** — we can't safely run the live test blind
- CJ inventory sync (RAT-39) moved from parking lot to **active queue** — prevents listing out-of-stock products during test
- Automated marketing (RAT-14) deprioritized to **Low** — deliberately deferred until the purchase loop is validated. Resume when the first real CJ order ships.

**Five new issues created from the audit:**

- **RAT-48** — If Stripe fails during an auto-refund triggered by a vendor SLA breach, the customer's refund gets stuck permanently with no retry. This is the highest-severity unhandled failure in the live order flow. Ticket defines a retry job and reconciliation table.
- **RAT-49** — The daily product discovery scan can silently complete with zero results (empty pipeline, no alert). This already happened once (PM-012). New issue adds consecutive-zero-scan alerting.
- **RAT-50** — The last manual step in the product discovery funnel: a human currently has to approve each experiment before the pipeline can auto-list it on Shopify. This ticket automates that gate behind a configurable confidence threshold and a feature flag.
- **RAT-51** — The system's seven monitoring jobs share a single execution thread. A slow margin sweep can delay the vendor SLA monitor, meaning a breach that should trigger auto-refunds in 15 minutes could take 30+ minutes. Ticket fixes thread pool configuration.
- **RAT-52** — Margin sweep and shipment tracking both load all records into memory at once. Fine today. At 10x scale (1,000+ active SKUs / orders), these become multi-minute operations. Ticket adds pagination.

**Dev store path clarified (RAT-53):**

New Urgent ticket capturing everything needed for a safe test purchase with dummy cards before touching real money. The complete setup requires exactly four configuration values from Shopify Partners — no code changes, no new API key type, no custom storefront work. Details below.

**Innovation backlog (RAT-54):**

Captured headless multi-channel storefront as a future option for when we're running on multiple sales channels simultaneously and Shopify's hosted storefront becomes a constraint. Parked until Phase 2.

---

## The Shopify Dev Store Question — Answered

This came up during the session and it's worth naming clearly because it could have caused significant wasted engineering effort.

**What we thought we might need:** A Shopify Storefront API key (a separate credential for building a custom buyer-facing frontend).

**What we actually need:** Only the Admin API access token — the same credential type we already use for everything else. Shopify's hosted storefront at `your-store.myshopify.com` handles product display, search, cart, and checkout without any additional integration. Our system pushes products in and pulls orders out through the Admin API. That's the entire integration surface.

The four values needed to run against a Shopify development store:
1. Store domain (`your-dev-store.myshopify.com`) — from Shopify Partners, free
2. Admin API access token — from the Custom App you create in that dev store
3. Webhook signing secret — generated alongside the access token
4. Stripe test API key (`sk_test_xxx`) — Stripe test mode is operationally identical to production with the same API surface; use test card `4242 4242 4242 4242`

**The headless question (Phase 2):** If we eventually want a unified storefront under our own domain aggregating Shopify, Amazon, and Etsy simultaneously, that's where the Storefront API becomes relevant. That work is captured as RAT-54 and should not start until we're operating on multiple channels at once.

---

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Margin monitoring + kill rules | Done | <30% net for 7 days → auto-pause; >5% refund → pause; >2% chargeback → pause + compliance |
| Vendor SLA monitoring + auto-refund | Done | 15-min checks; auto-refund on breach; one retry gap captured in RAT-48 |
| Shopify product listing (auto) | Done | SKU LISTED transition → product published on Shopify automatically |
| Order intake (Shopify webhook) | Done | Full HMAC verification, replay protection, deduplication |
| CJ order placement + tracking sync | Done | Auto-fulfilled, tracking fed back to Shopify |
| Capital reserve management | Done | Nightly reconciliation, 10-15% reserve floor |
| Compliance pre-listing checks | Done | 4 concurrent checks: IP, claims, processor rules, sourcing/sanctions |
| Demand signals (YouTube + Reddit) | **Code done, not live** | RAT-22 shipped the code; API credentials not yet configured |
| Observability + alerting | **Not Started** | RAT-44 now Urgent — needed before first live test |
| Shopify dev store + Stripe test mode | **Not Started** | RAT-53 — gate-zero for the purchase test |
| Shopify refund webhook | **Not Started** | RAT-43 — needed to handle returns from live test |
| Stripe refund retry | **Not Started** | RAT-48 — highest-severity open failure path |
| Auto-experiment promotion | **Not Started** | RAT-50 — last manual gate in discovery funnel |
| Automated marketing + SEO | Deferred | RAT-14 — resume after first order ships |

---

## What's Next

**1. RAT-53 — Shopify dev store + Stripe test mode (Urgent)**
Create the dev store, configure the four values, boot the app, and verify a product lists automatically when a SKU passes the cost gate. This is the gate-zero before any real-money activity. The runbook for this lives in RAT-42.

**2. RAT-44 — Observability (Urgent)**
Wire structured logging and alerting before running any live transaction. Without this, a silent failure (empty demand scan, failed CJ order, Stripe error) looks like success and goes unnoticed.

**3. RAT-43 — Shopify refund webhook (High)**
Complete the order lifecycle for the test: buy the product, then trigger a test refund and verify the full capital adjustment flows through. RAT-53 step 11 depends on this.

**4. RAT-48 — Stripe refund retry (High)**
The safety net for the live test. If anything goes wrong with a refund, orders should not silently strand.

**5. Enable YouTube and Reddit demand signals**
This is a configuration task, not a code task. Register Google Cloud and Reddit API credentials and set the environment variables. The pipeline is already built (RAT-22). This doubles the demand signal coverage at zero engineering cost.

---

## Risks & Decisions Needed

- **YouTube + Reddit credentials:** We need a Google Cloud project (free) for the YouTube Data API key and a Reddit app registration (free) for OAuth credentials. Neither requires engineering work — just account setup. Whoever has the Google/Reddit accounts should set these up before the next demand scan run. Without them, discovery is running on two sources instead of four.

- **Auto-terminate flag:** The kill-window auto-termination (`portfolio.auto-terminate.enabled`) defaults to `false` — it produces recommendations but doesn't act on them. We can enable this once we've validated that the kill rules are firing correctly during the live test. Worth flagging as a post-test decision.

---

## Session Notes

- The strategic audit used live graph traversal across 2,927 codebase nodes — every finding is grounded in actual code, not assumptions
- The Storefront API clarification alone likely saved several days of misguided engineering work
- Unblocked's institutional history confirmed the RAT-22 credential gap is a known open item, not a regression — the code shipped clean, the config step was deferred
