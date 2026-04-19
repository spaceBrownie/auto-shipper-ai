# NR-012: Gate-Zero is Ready — First End-to-End Test with Zero Real Money

**Date:** 2026-04-18
**Linear:** [RAT-53](https://linear.app/ratrace/issue/RAT-53) · PR #52
**Status:** Completed (code) · Operator action required (execution)

---

## TL;DR

The infrastructure for a safe end-to-end test of the full order pipeline is merged. Once you complete the operator setup below (about 90 minutes of clicking through dashboards), we can list a product, buy it with a dummy card, receive the order webhook, and exercise the supplier-order path **without a single dollar of real money moving**. This is the gate we pass before the live-money test can happen.

## What Changed

PR #52 (the RAT-53 work) shipped five things that unlock the safe test:

- **A way to drive a product onto a Shopify storefront through our automated pipeline**, not manual data entry. This exercises the real listing code path, not a short-cut.
- **A "dry-run" safety switch on the supplier-order side.** When this is on, the system logs what it *would have* ordered from CJ but makes zero API calls to CJ. No real supplier order dispatched. No real money.
- **A webhook capture system.** When Shopify sends us an order notification, we archive the exact bytes of that notification to disk. Future regression tests use these as ground truth instead of hand-written guesses.
- **A pre-flight safety audit** (Gradle command). Before booting the app for a dev-store run, one command verifies all credentials are test-mode — fails loud if anything looks like a live-money key.
- **A 12-step runbook** (new Section 0 and Section 12 in `docs/live-e2e-runbook.md`) walking through the full dev-store setup in operator-friendly order.

Four bugs were caught by our automated code reviewer during the PR cycle and fixed before merge — filter-lifecycle bug that would have caused disk writes on every production request, a missing `LIMIT 1` on a database query, a filename-collision bug when webhooks arrive in the same millisecond, and a user-input sanitization gap. **Zero of these reach production.** The review-fix loop did that on its own — 4 cycles, 4 fixes, approval, no human babysitting.

## Why This Matters

The real-money live test is the single most expensive test we run — any bug in the pipeline means a real customer's card gets charged for an order that may not fulfill correctly. Historically, PM-013 and PM-015 documented that "worked in test, broke in prod" is the default mode for webhook-driven integrations. Gate-zero exists so we catch those bugs when the cost of catching them is zero dollars.

Two things changed the shape of this FR mid-flight:
1. **We discovered CJ has a sandbox-account program.** Originally we planned to route around this with a code-level "dry-run" switch that makes zero CJ API calls. You can (and should) still apply for the sandbox account in parallel — once approved, we flip the switch off and exercise the full CJ API surface against their sandbox instead of simulating it.
2. **We identified a pre-existing gap** in our Shopify inventory check and fixed it as part of this FR (the inventory-check adapter was using our internal SKU identifier instead of Shopify's real inventory-item-id, which would have rejected every order under the production profile). This was called out in the RAT-42 runbook as "known gap, blocks E2E" — now resolved.

## Operator Setup Checklist (roughly 90 minutes)

Do these in order. Each step has a known-good state; if you hit an error, stop and flag.

### Step 1 — Shopify Partners + Dev Store (~20 min)
1. Sign up for Shopify Partners (free, no card required): https://www.shopify.com/partners
2. Create a Development Store. Note the `*.myshopify.com` subdomain — that's our dev-store URL.
3. Enable the hosted storefront so products are publicly browseable.
4. In the dev store admin, install a Custom App. Grant these scopes: `write_products, write_orders, read_orders, write_fulfillments, read_fulfillments`.
5. Capture two values to a secure notes file (we'll paste them into `.env` later):
   - Admin API access token (starts with `shpat_`)
   - Webhook signing secret (starts with `whsec_`)

### Step 2 — Stripe Test Mode on the Dev Store (~10 min)
1. In Shopify dev store admin: Settings → Payments → set up Stripe as provider.
2. In Stripe dashboard: create a test account if you don't have one already. **Never use live-mode keys here.**
3. Configure Stripe inside Shopify with **test-mode** keys (`pk_test_...` for publishable, `sk_test_...` for secret).
4. Fallback: if Stripe approval drags, use Shopify's built-in Bogus Gateway with card number `1`. Less realistic, but unblocks the test.

### Step 3 — CJ Path Selection (~5 min — two options)
Pick ONE:
- **(a) Sandbox (preferred — email your CJ agent now, may take days):** Request a sandbox account. Sandbox orders run against the same API but **do not dispatch real supplier orders**. The account must be at $0 balance before application, and the switch is **irreversible**, so keep your production CJ account separate. Use the sandbox token in `.env` and leave the dry-run switch off.
- **(b) Dry-run (ready today):** Use any CJ token and flip `AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true` in `.env`. The system logs what it would have ordered but makes zero CJ API calls. Financially identical to sandbox for this test — but you won't exercise the real CJ API.

**Either path gets us to a safe test.** Path (b) is available right now; path (a) is the follow-up.

### Step 4 — Populate `.env` + Run Pre-Flight Audit (~10 min)
1. Copy `.env.example` to `.env` (gitignored).
2. Fill in every Shopify, Stripe, and CJ value from the previous steps.
3. Run the audit: `./gradlew devStoreAuditKeys`
4. Expected output: `PASS — Stripe ****xxxx, Shopify *-dev.myshopify.com (****xxxx), CJ token ****xxxx`. Last-4 digits only — full keys are never printed.
5. If any check fails: **halt and flag**. The audit fails loud on `sk_live_` prefixes, missing webhook secrets, or non-dev-store domains. Never hack around it.

### Step 5 — Boot + ngrok Tunnel (~10 min)
1. Start the database: `./gradlew flywayMigrate` (applies schema migrations up through V23).
2. Start the app: `./gradlew bootRun`. Verify `GET /actuator/health` returns `UP`.
3. Start ngrok to expose localhost to Shopify: `ngrok http 8080`. Note the `https://` URL.
4. In the Shopify dev store admin: register a webhook for the `orders/create` topic, pointing at `{ngrok-url}/webhooks/shopify/orders-create`.

### Step 6 — Drive the Test (~30 min)
1. Trigger the automated listing: `curl -u admin:$DEV_ADMIN_TOKEN -X POST http://localhost:8080/admin/dev/sku/{id}/list`
2. Verify product appears on the dev storefront.
3. In an incognito browser: add the product to cart, check out with card `4242 4242 4242 4242`. Any future expiry, any CVC.
4. Observe:
   - `webhook_events` row written in the database
   - `orders` row with status `CONFIRMED`
   - CJ log line: either "sandbox order placed," "[DEV-STORE DRY RUN]," or an explicit error
   - A new file under `docs/fixtures/shopify-dev-store/{date}/` containing the webhook body

### Step 7 — Post-Test Cleanup (~5 min)
1. Cancel the CJ order via dashboard **only if** you used the sandbox path AND the CJ dashboard shows a real order (sandbox orders normally don't need cancellation — but verify).
2. Commit archived webhook payloads into the feature branch after redacting any PII per the directory's README.
3. Rotate the admin token and webhook secret.
4. Flip `AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED=false` in `.env`.

## Status Snapshot

| Area | Status | Notes |
|---|---|---|
| Code infrastructure (PR #52) | Done | Merged clean. 655 automated tests passing. |
| Pre-flight safety audit | Done | `./gradlew devStoreAuditKeys` ready to run. |
| Runbook (Section 0 + Section 12) | Done | 12-step operator walkthrough. |
| Shopify Partners account | Not Started | Human action — Step 1. |
| Shopify dev store created | Not Started | Human action — Step 1. |
| Stripe test-mode keys | Not Started | Human action — Step 2. |
| CJ sandbox application | Not Started | Human action — Step 3a (parallel, multi-day). |
| `.env` populated for dev-store run | Not Started | Human action — Step 4. |
| First gate-zero test run | Blocked on operator setup | Zero code work remaining. |
| Live-money test (RAT-42) | Blocked on gate-zero passing | Do not start until gate-zero is clean. |
| Refund webhook handler (RAT-43) | Not Started | Explicitly out of scope for gate-zero. |

## Financial Posture

Zero real money at risk during gate-zero, enforced three ways:

- **Shopify dev store** is an isolated instance. No live customers. No public marketing.
- **Stripe test mode** processes the full payment lifecycle (authorize → capture → refund) at our app's API surface identically to production — but no card is ever charged. Card `4242 4242 4242 4242` is Stripe's official test card.
- **CJ dry-run switch** short-circuits the supplier-order call entirely. When on, zero HTTP calls leave the process for CJ. There's a dedicated automated test (T-65) that verifies this with every build.

Costs: Shopify Partners account is free. Stripe test mode is free. CJ sandbox application is free (operator time, ~multi-day waiting). ngrok free tier is sufficient. Total out-of-pocket before the real-money RAT-42 test: **$0.**

## What's Next

- **Now:** Human executes Steps 1–4 of the operator setup (Shopify Partners, dev store, custom app, Stripe test keys, `.env`). This is the gating work.
- **In parallel:** Email CJ agent to apply for a sandbox account. Multi-day response time. Not a blocker for gate-zero.
- **After Steps 1–4:** Denny runs the pre-flight audit with Nathan to validate the setup, then we execute the test run together.
- **After gate-zero passes cleanly:** We're cleared to open the RAT-42 live-money runbook. The real test uses the same code path, same operator procedure, only the keys change from test-mode to live.

## Risks & Decisions Needed

- **CJ Path Selection:** Path (a) requires CJ agent approval and is irreversible. Path (b) ships today but doesn't exercise the real CJ API. **Ask:** email CJ to apply for sandbox today so (a) is available within days; run gate-zero on path (b) in the meantime.
- **Operator-only test scenarios:** 10 of the 14 end-to-end test scenarios require Nathan (or another human) to click through dashboards and perform a browser purchase. We cannot automate browser checkout without adding a headless-browser layer that would exceed scope. **Ask:** confirm Nathan (or an assigned operator) will drive Steps 5–7. ~30 minutes once setup is complete.

## Session Notes

- PR #52 shipped with 655 automated tests passing and went through 4 independent code-review cycles from our automated reviewer (`unblocked[bot]`). Each cycle surfaced a real bug, each fix landed with a new regression test. The most serious was a Spring-framework filter that would have run on every HTTP request in production due to an annotation mistake — caught and fixed before merge. The autonomous review-fix loop exit-approved the PR without human intervention in the review cycle, which is the first time that's happened on this project.
- A separate post-mortem (PM-021) documents what we learned from this session, including proposed workflow enforcement via Claude Code hooks. That's filed as [RAT-55](https://linear.app/ratrace/issue/RAT-55) under the Innovation tag — doesn't block anything.
- Total session produced: 10 production files, 13 new test files, 6 new API fixtures, 5 config/docs files, 1 new Flyway migration (V23).
