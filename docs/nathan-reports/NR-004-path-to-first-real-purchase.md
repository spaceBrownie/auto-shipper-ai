# NR-004: What's Left Before We Can Buy Something and Have It Show Up

**Date:** 2026-03-21
**Linear:** Tickets to be created
**Status:** Planning

---

## TL;DR

The system can discover products, validate margins, list them on a real Shopify store, and protect capital if things go wrong. But it can't yet detect when a customer buys something, place an order with a supplier, or tell the customer their package shipped. Four integrations close the loop: Shopify order detection, supplier order placement, tracking number ingestion, and customer emails. After that, we can buy one of our own products, have it show up at our front door, and then return it for a refund — proving the full cycle works end-to-end.

## The Full Purchase Pipeline — What's Built vs. What's Missing

Green = built and tested. Red = not started. Blue = handled by Shopify (not our code).

![Path to First Purchase](path-to-purchase.svg)

## Where We Are Right Now

The backend machinery is complete and tested. Here's the honest picture of what works and what's a gap, mapped against the real journey of a product reaching someone's house:

| Step | What Should Happen | Today |
|------|-------------------|-------|
| Product goes live on Shopify | System auto-creates the listing with correct price, title, and SKU | **Works.** Tested against real Shopify API docs. Needs store credentials configured. |
| Customer finds it and buys it | Shopify handles cart, checkout, and payment | **Works.** This is Shopify's job, not ours. |
| We know someone bought it | Shopify sends a webhook when an order is placed | **Gap.** We have no listener. The system is blind to sales. |
| We order it from the supplier | System places a purchase order with the vendor (dropship/3PL) | **Gap.** The vendor module tracks SLAs and scores reliability, but never actually orders anything. |
| Supplier ships to customer | Vendor provides a tracking number | **Gap.** No way for vendor tracking data to flow back into the system. |
| Customer gets shipping email | System sends "your order shipped" with tracking link | **Gap.** Notifications are logged to the database but never sent. |
| We track the package | System polls UPS/FedEx/USPS every 30 minutes for status | **Works.** Just needs a tracking number to start polling. |
| Package delivered | Carrier confirms delivery, system records it, credits the reserve | **Works.** The full event chain (delivery → capital record → reserve credit) is tested and verified. |
| Customer wants a refund | System refunds via Stripe, updates order status, adjusts reserve | **Partial.** Stripe refund works. But no return shipping label, no return tracking, no restocking. |
| Refund rate monitored | System detects if refund rate > 5% and auto-pauses the SKU | **Works.** Portfolio-wide systemic refund detection also works. |

## The Four Things We Need to Build

In priority order — each one feeds the next:

### 1. Shopify Order Webhook Listener

**What it does:** When a customer checks out, Shopify sends us a notification. We receive it, verify it's legitimate (HMAC signature check), extract the order details (items, quantities, shipping address, payment reference), and create an internal order in PENDING status.

**Why it's first:** Without this, the system is deaf to sales. Everything downstream — ordering from the supplier, tracking shipment, crediting the reserve — requires knowing that a sale happened. Right now the only way to create an order is to manually call an API endpoint.

**What we get after:** The system knows when money comes in. Orders appear in the dashboard automatically. The capital module starts tracking revenue.

### 2. Supplier Order Placement

**What it does:** When an order is confirmed, the system calls the supplier's API to place a purchase order with the customer's shipping address. For CJ Dropshipping (our primary source for demand signals), this means calling their order creation API. The supplier responds with an order ID and eventually a tracking number.

**Why it's second:** This is where the zero-capital model becomes real. The customer's payment has already been captured by Shopify/Stripe. We use that payment to fund the supplier order. No inventory, no upfront cost — exactly the model we designed.

**What we get after:** Orders actually get fulfilled. A real product starts moving toward a real customer.

### 3. Tracking Number Ingestion

**What it does:** Two parts. First, receive the tracking number from the supplier (either via a callback webhook or by polling their API). Second, feed it into our existing shipment tracker, which already polls UPS/FedEx/USPS every 30 minutes and auto-detects delivery.

**Why it's third:** The shipment tracker is fully built and tested — it just needs something to track. Once we have the tracking number from the supplier, the whole delivery detection → capital record → reserve credit chain fires automatically. We proved this works in the E2E test today.

**What we get after:** Real-time shipment visibility. Delay detection works. Delivery auto-triggers the capital event chain. The dashboard shows live tracking status.

### 4. Customer Email Notifications

**What it does:** Wire up an email sender (SendGrid, AWS SES, or Shopify's built-in email system) to the existing notification infrastructure. The system already creates notification records in the database for order confirmation, shipping updates, and delivery — it just logs them instead of sending them.

**Why it's fourth:** Shopify sends some of these emails natively (order confirmation, basic shipping notification). So this is a polish item, not a hard blocker for the test purchase. But for a real operation, we want to own the customer communication.

**What we get after:** Professional customer experience. Order confirmation, shipping with tracking link, delivery confirmation — all automated.

## The Test Purchase: What the Proof Run Looks Like

Once these four integrations are built, here's the exact test:

1. **Set up a real Shopify store** with our API credentials configured
2. **Let the system discover and list a product** — run the demand scan, let it find a candidate, walk it through compliance → cost gate → stress test → list on Shopify
3. **Buy it ourselves** — go to the Shopify store, add to cart, check out with a real card
4. **Watch the system react** — webhook fires, order appears in dashboard, supplier gets the purchase order, tracking number flows back, shipment tracker starts polling
5. **Wait for delivery** — package arrives at our door. System auto-detects delivery via carrier API, credits the reserve, records the capital event
6. **Return it** — initiate a return, verify the refund hits Stripe, watch the refund rate update in the portfolio view

That's the full cycle. Discovery to doorstep to refund.

## What About Returns?

The return flow has partial support:

- **What works:** Order can transition to REFUNDED or RETURNED status. Stripe refund API works. Refund rate tracking and portfolio-level refund pattern detection work.
- **What's missing:** No return shipping label generation, no return tracking, no restocking logic, no return authorization workflow.
- **For the test purchase:** We can manually trigger a refund via Stripe and update the order status. The capital module will correctly adjust the reserve and refund rate. Not fully automated, but enough to prove the financial loop closes.
- **For production:** We'd need a return authorization flow, possibly integrated with the carrier for return labels. This is a Phase 2 item — the happy path (buy → deliver → profit) is more important to prove first.

## Timeline Estimate

These are four focused integrations, not architectural overhauls. The system's event-driven architecture means each one plugs into existing patterns:

| Integration | Depends On | Complexity |
|-------------|-----------|------------|
| Shopify order webhook | Nothing — standalone | Medium (HMAC verification, order mapping, idempotency) |
| Supplier order placement | Webhook listener (need orders to place) | Medium-High (CJ API integration, address mapping, error handling) |
| Tracking ingestion | Supplier integration (need tracking numbers) | Low-Medium (poll or callback, feed into existing tracker) |
| Customer emails | Webhook listener (need orders to notify about) | Low (SendGrid/SES SDK, template system, existing notification records) |

The webhook listener and customer emails could be built in parallel since they don't depend on each other. Supplier integration and tracking ingestion are sequential.

## Status Snapshot

| Area | Status | Notes |
|------|--------|-------|
| Product discovery (4 demand sources) | Done | CJ, Google Trends, YouTube, Reddit |
| Cost verification (13 components) | Done | Live carrier rates, platform fees, processing fees |
| Stress testing (50%/30% floors) | Done | 2x shipping, +15% CAC, +10% supplier, 5% refund, 2% chargeback |
| Compliance gate (4 concurrent checks) | Done | IP, claims, processor, sourcing |
| Shopify listing (create/pause/archive) | Done | Verified against real API docs, 21 contract tests |
| Dynamic pricing | Done | Cost signal reactions with auto-adjust/pause/terminate |
| Vendor SLA monitoring | Done | 30-day rolling window, auto-suspend on breach |
| Capital protection (reserve + margins) | Done | 6h sweeps, auto-pause on breach, full audit trail |
| Portfolio orchestration | Done | Experiments, kill window, priority ranking, refund patterns |
| Order lifecycle via REST | Done | Create, confirm, ship, deliver — all tested E2E |
| Stripe refunds | Done | Auto-refund on SLA breach, manual refund via API |
| Shipment tracking (carrier polling) | Done | UPS/FedEx/USPS, delay detection, delivery auto-detect |
| **Shopify order webhook** | **Not Started** | **Blocker #1 for test purchase** |
| **Supplier order placement** | **Not Started** | **Blocker #2 for test purchase** |
| **Tracking number ingestion** | **Not Started** | **Blocker #3 for test purchase** |
| **Customer email notifications** | **Not Started** | Nice-to-have for test; needed for production |
| **Return authorization flow** | **Not Started** | Manual workaround for test purchase |

## What's Next

- **Create Linear tickets** for the four integrations above
- **Start with the Shopify order webhook** — it unblocks everything else and is the simplest to test (just buy something on the store)
- **Decision needed:** Which supplier API do we integrate first? CJ Dropshipping is our primary demand signal source, so it's the natural first choice. But if we want to test with a different supplier for the proof run, now's the time to decide.

## Session Notes

- This assessment came from a thorough audit of every module's real vs. stub implementations. The architecture is sound — the event-driven patterns mean each integration plugs in cleanly. The hard work (cost modeling, margin protection, capital management) is done and tested.
- The system is roughly 70% of the way to a real purchase. The remaining 30% is "last mile" integrations — connecting to external systems that trigger and fulfill orders. No architectural changes needed.
- 40 automated tests were added today (NR-003) covering the exact transaction boundary patterns these new integrations will use. When we build the webhook listener and supplier integration, the testing infrastructure is ready.
