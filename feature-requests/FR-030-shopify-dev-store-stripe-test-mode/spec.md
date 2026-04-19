# FR-030: Shopify Dev Store + Stripe Test Mode — Safe E2E Purchase Validation

**Linear ticket:** RAT-53
**Priority:** Urgent
**Status:** Phase 2 (Specification)
**Predecessor:** FR-029 (RAT-42) — live E2E test infrastructure, merged
**Successor gate:** enables the real-money execution of the RAT-42 runbook
**Related (not in scope):** RAT-43 (refund webhook), RAT-44 (observability)
**Predecessor (CJ parse correctness):** FR-028 (RAT-47) — CJ adapter/fixture/test reconciliation, PR #48 merged

---

## Problem Statement

FR-029 (RAT-42) built the physical infrastructure to run the full order pipeline against real external APIs: a Docker packaging, HMAC-verified Shopify webhook controller, a CJ supplier-order adapter, a CJ tracking webhook, and a comprehensive runbook (`docs/live-e2e-runbook.md`). What it does **not** do is prove the pipeline is correct before any real money moves.

A first attempt with live Shopify + live Stripe + a real buyer's card would expose the pipeline to the full cost of every bug class we already know is latent in the system:

- **PM-013 redux** — adapter fixtures are known to drift from real payloads. A field-mismatch at line-item resolution silently marks the order `FAILED` but the customer's card has already been charged. Recovery requires a manual Shopify refund, and the refund handler (RAT-43) is not yet built.
- **PM-015 transaction cascade on first real traffic** — the `@Async + AFTER_COMMIT + REQUIRES_NEW` annotation stack on `ShopifyOrderProcessingService` has only been exercised against WireMock so far. Shopify enforces a 5-second webhook timeout; exceeding it causes Shopify to disable the webhook endpoint, blocking downstream recovery.
- **PM-020 CJ parse drift** — addressed by FR-028 (RAT-47, PR #48 merged): all CJ adapters, fixtures, and tests are reconciled against the verified YAML spec. Not an open risk for this FR, but dev-store is still the first real-traffic exercise of the reconciled CJ code path.
- **Known gap: `ShopifyInventoryCheckAdapter` SKU-UUID/inventory-item-id mismatch** — the runbook (Section 10) documents this will block order creation under the non-local profile. Without a fix, the acceptance criterion "one purchase end-to-end" is unreachable.
- **Known gap: DELIVERED transition unreachable** — the capital-module revenue recording path has no real trigger; this is out of scope for this FR but is noted so we do not pretend otherwise.

The cost of catching any of these with a live card is (at minimum) a refund cycle, (more likely) a test-buyer customer-service moment, and (worst-case) a disabled webhook endpoint that hides the real failure. The cost of catching them here, against a Shopify **development store** with **Stripe in test mode** and the dummy card `4242 4242 4242 4242`, is zero.

FR-030 is the gate-zero validation: an auditable, documented, repeatable pass of the full pipeline with no real money at risk. Only after this gate is clean do we execute the RAT-42 runbook for real.

The shape of the work is deliberately lopsided: **~80% operational setup, config surface hardening, and runbook authoring; ~20% code** (only the narrow guardrails and the single known gap — `ShopifyInventoryCheckAdapter` — needed to make the pipeline reachable).

---

## Business Requirements

### 2.1 Dev-store provisioning (operational)

- **BR-1:** A Shopify development store MUST be provisioned via Shopify Partners, publicly reachable at `*.myshopify.com`, with the hosted storefront enabled so products are browsable by a buyer.
- **BR-2:** A Custom App MUST be installed on the dev store with Admin API scopes: `write_products`, `write_orders`, `read_orders`, `write_fulfillments`, `read_fulfillments`. The Admin API access token (`shpat_xxx`) and webhook signing secret (`whsec_xxx`) MUST be captured.
- **BR-3:** Stripe test mode MUST be configured as the dev store's payment provider, using Stripe **test** API keys (`sk_test_…`, `pk_test_…`). The Shopify Bogus Gateway is the documented fallback **only** if Stripe partner approval delays setup.
  - **Rationale (Phase 1 decision):** Stripe test mode exercises the full payment API surface (authorize/capture/refund) and produces webhook payloads identical to production. Bogus Gateway tests less of the real payment path and must not be treated as the primary target.

### 2.1b CJ sandbox account (operational)

- **BR-3a:** A CJ **sandbox account** MUST be provisioned and its access token used in the dev-store test `.env` as `CJ_ACCESS_TOKEN`. Per CJ's API docs: a sandbox account is a test account under the production environment — same API surface, same endpoints, same token format — that does NOT dispatch real supplier orders. Application is not self-serve; it goes through the CJ account agent. The switch is irreversible once configured, so it MUST be a separate account from the production account (hold both).
  - **Rationale (reopening a Phase 1 blind spot):** an earlier draft of this spec assumed CJ had no test mode and the dev-store run would dispatch a real supplier order that must be cancelled post-hoc. That is incorrect. Using a sandbox account removes the only real-money path in the pipeline outside of Shopify/Stripe test mode. Without sandbox, this FR's "no real money" claim (NFR-3) is violated at CJ.
  - **If sandbox provisioning is blocked by CJ agent delay:** the operator MAY fall back to a production CJ account, but MUST configure the new `autoshipper.cj.dev-store-dry-run=true` kill-switch (see BR-6b) that short-circuits `CjSupplierOrderAdapter.placeOrder()` to a logged no-op before proceeding. The spec will NOT allow an uncaveated real-order path to be the default.

### 2.2 Secret handling (operational)

- **BR-4:** Dev-store credentials (Shopify access token, Shopify webhook secret, Stripe test keys, CJ access token, CJ webhook secret) MUST NOT be committed. They MUST live in a gitignored `.env` file consumed by `docker compose` / `bootRun`, identical to the pattern FR-029 established.
  - **Rationale (Phase 1 decision):** introducing Vault / 1Password / AWS Secrets Manager is out of scope for gate-zero validation. `.env` is already gitignored and excluded from the Docker image; the spec's "stored in secrets manager (not in code)" bar is met by "not in code" — an actual secrets manager is a separate hardening ticket.

### 2.3 Automated listing trigger (code + operational)

- **BR-5:** The test script MUST drive product creation through the existing portfolio orchestration path: `LaunchOpportunity → LaunchReadySku → ShopifyListingAdapter → product visible on the hosted storefront`. A manual `curl` that inserts a `platform_listings` row directly is NOT acceptable; it would not exercise the code path the acceptance criterion requires.
  - **Rationale (Phase 1 decision):** the "automated pipeline" AC is explicit about exercising the real listing code path. If no existing endpoint can trigger a `LaunchOpportunity` under operator control, Phase 3 must expose a minimal admin endpoint (e.g. `POST /admin/sku/{id}/list`) gated behind a new config property (default disabled) so it is never reachable in production. Phase 3 chooses the exact mechanism; the spec only mandates the behavior.

### 2.3b CJ dry-run kill-switch (code — conditional on sandbox availability)

- **BR-6b:** A new configuration property `autoshipper.cj.dev-store-dry-run` (default `false`) MUST be added to `CjSupplierOrderAdapter`. When `true`, `placeOrder()` MUST short-circuit: log the would-have-been request at INFO with a clear `[DEV-STORE DRY RUN]` marker, return a deterministic stub `SupplierOrderResult.success` with a fake CJ order id (e.g. `"dry-run-<uuid>"`), and NOT make any HTTP call to CJ. Success Criterion 7 is satisfied by the INFO log line when this flag is on.
  - **Rationale:** defense in depth — even with a CJ sandbox account (BR-3a), a misconfiguration or accidental swap of `.env` files could send `createOrderV2` to a production CJ account. The kill-switch gives operators an explicit, code-level way to prevent any CJ side effect during the gate-zero run, independent of which account's token is populated.
  - **Usage guidance documented in runbook:** the default path is sandbox-account + `dev-store-dry-run=false` (to exercise the real CJ call code path). The dry-run flag is the documented fallback when sandbox is not yet provisioned OR when an operator wants a pure code-path smoke-test with zero CJ side effect.

### 2.4 Inventory-check gap (code)

- **BR-6:** The `ShopifyInventoryCheckAdapter` SKU-UUID / `inventory_item_id` mismatch documented in `docs/live-e2e-runbook.md` Section 10 ("Known Gaps") MUST be closed before the dev-store purchase is attempted. The acceptance criterion "one purchase completed end-to-end" is not satisfiable while this adapter rejects every SKU.
  - **Rationale (Phase 1 decision):** Phase 3 selects among three implementation options — (a) feature-flag the inventory check off for the dev-store profile, (b) seed an `inventory_item_id` mapping in fixtures + a new DB column, or (c) fix the adapter to resolve `inventory_item_id` via `/admin/api/2025-01/variants/{variant_id}.json` at order-creation time. Options (a) and (c) are both viable for a gate-zero; option (b) is preferred if the mapping is needed long-term. Spec mandates the gap is closed; Phase 3 picks how.

### 2.5 Runbook extension (operational)

- **BR-7:** `docs/live-e2e-runbook.md` MUST be extended with a new Section 0 — "Pre-flight key audit" — that fails loud if any configured key is in live/production mode. The audit MUST check, at minimum:
  - Stripe app-level key MUST have the prefix `sk_test_` (see NFR-2 below).
  - Shopify store domain MUST end in `.myshopify.com` (not a custom production domain).
  - Operator MUST record the last 4 digits of the Stripe key and the dev-store subdomain in a pre-flight checklist before proceeding.
  - **Rationale (Phase 1 decision, open question 5):** a code-level guard on `*.myshopify.com` is brittle because that suffix is also valid for paid production stores. A runbook checklist that the operator signs off is more reliable and matches how the rest of the live test is gated.
- **BR-8:** The runbook MUST be extended with a new section walking through (in order) dev-store creation, custom app + scopes, Stripe test-mode configuration, `.env` population with test keys, the automated SKU → listing path, the hosted-storefront purchase with card `4242 4242 4242 4242`, and verification of each downstream step. This section MUST be cross-linked from FR-029's runbook so an operator can follow one continuous document.

### 2.6 Webhook payload archival (operational + code)

- **BR-9:** Every webhook payload received by the app during the dev-store test run (Shopify `orders/create`, Shopify `orders/paid` if it fires, Shopify fulfillment events, CJ tracking) MUST be archived under `docs/fixtures/shopify-dev-store/` as a dated `.json` file. These become the source of truth for future WireMock fixture regeneration, preventing PM-013-style fixture drift.
  - **Rationale (Phase 1 risk-surfacing):** this is the first time we will see real dev-store webhook payloads. If we do not capture them, the next regression run will inherit today's WireMock fixtures which may still be wrong.

### 2.7 Refund out-of-scope (operational)

- **BR-10:** Refund path verification is explicitly out of scope; RAT-43 owns the refund webhook handler. The test script stops after fulfillment sync. The runbook MUST note this so an operator does not attempt a refund and conclude the pipeline is broken.

---

## Success Criteria

The FR is complete when all of the following are independently observable:

1. **Dev store live:** `curl -s https://<dev-store>.myshopify.com/` returns a 200 with the storefront HTML.
2. **App boots against dev-store credentials:** `./gradlew bootRun` (non-local profile) starts clean, `GET /actuator/health` reports `UP`, with `.env` populated by dev-store + Stripe test + CJ credentials.
3. **Pre-flight audit passes:** the new runbook Section 0 checklist is signed off; Stripe key begins `sk_test_`; Shopify domain is the dev-store subdomain.
4. **Automated listing succeeds:** a SKU walked through the state machine to `LISTED` results in a product visible on the hosted storefront — verified by browser navigation AND by `GET https://<dev-store>.myshopify.com/products/<handle>.json`.
5. **Buyer purchase completes:** a human buyer navigates the dev store, adds the product to cart, checks out with card `4242 4242 4242 4242`, and Shopify's order-confirmation page is reached without error.
6. **Webhook delivered and processed:** `orders/create` webhook arrives via ngrok inspector within 5 seconds; a `webhook_events` row is written with `channel='shopify'`; an `orders` row is written with `status='CONFIRMED'` and `channel_order_id` matching the Shopify order.
7. **CJ supplier-order placement attempted:** logs show ONE of:
   - (a) sandbox path: `CJ order placed successfully` against a CJ **sandbox account** (BR-3a), OR
   - (b) dry-run path: `[DEV-STORE DRY RUN] would have placed CJ order …` when `autoshipper.cj.dev-store-dry-run=true` (BR-6b), OR
   - (c) a specific CJ error from the API (sandbox path, non-2xx).

   A silent failure (no log line, no `failure_reason`) is NOT acceptable — "attempted" is the bar, not "succeeded." Operator MUST record which of (a)/(b)/(c) applied in the runbook execution log.
8. **Fulfillment sync reached:** either (a) a simulated CJ tracking webhook drives the `OrderShipped → ShopifyFulfillmentSyncListener` path to completion, OR (b) the operator explicitly records why Step 7 blocked before this step.
9. **Webhook payloads archived:** files exist under `docs/fixtures/shopify-dev-store/YYYY-MM-DD/` for every webhook observed in ngrok during the run.
10. **Runbook updated and merged:** `docs/live-e2e-runbook.md` contains Section 0 (pre-flight audit) and the new dev-store walkthrough section; `git log` shows the commit on the FR-030 branch.
11. **No real money moved:** Stripe test dashboard shows the test charge; the live Stripe dashboard shows zero activity for the test window.

---

## Non-Functional Requirements

- **NFR-1 (Webhook SLO):** The Shopify `orders/create` webhook handler MUST return 200 within 5 seconds as observed by ngrok. The `@Async + AFTER_COMMIT + REQUIRES_NEW` stack on `ShopifyOrderProcessingService` is the mechanism; this FR is the first real-traffic exercise of it, and a smoke-test assertion of the <5s end-to-end response time MUST be documented in the runbook verification section.
- **NFR-2 (Test-key-only constraint):** The application's own `STRIPE_SECRET_KEY` (used by `StripeRefundAdapter` and any future Stripe integration) MUST begin with `sk_test_`. Even though payment capture is owned by Shopify on the dev store, our app still holds a Stripe key for refund operations. A live-mode key in our app's environment would allow a bug in a future codepath to issue a real refund. The pre-flight audit (BR-7) enforces this.
- **NFR-3 (No real-money path executable):** No codepath exercised during this test may transact real money. Concretely: Stripe is in test mode at the Shopify integration level; our app's Stripe key is `sk_test_`; and CJ calls run against EITHER a sandbox account (BR-3a) OR the `dev-store-dry-run=true` kill-switch (BR-6b) — never against a production CJ account with the kill-switch off. The runbook MUST reject starting the test run if both (a) the configured CJ token is a production-account token AND (b) `autoshipper.cj.dev-store-dry-run=false`.
- **NFR-4 (Secrets never committed):** No real key — Shopify, Stripe, or CJ — may appear in any file tracked by git. The existing `.gitignore` covers `.env`; the spec makes this explicit as a non-negotiable. A pre-commit or review-time scan is NOT required for this FR but SHOULD be considered in a follow-up.
- **NFR-5 (Repeatability):** A second operator, given only the updated runbook + a fresh Shopify Partners account + a fresh Stripe account, MUST be able to reproduce the full test in one business day. The runbook is the deliverable, not tribal knowledge.
- **NFR-6 (First real-traffic CJ exercise):** FR-028 (RAT-47) reconciled all CJ adapters and fixtures against live API responses, so parse-drift is no longer a documented outstanding risk. However, dev-store is still the first real-traffic exercise of the reconciled CJ code path under live webhooks. Any new parse failure observed here MUST be captured in logs + `orders.failure_reason` and triaged before the real-money RAT-42 runbook executes.

---

## Dependencies

- **FR-029 (RAT-42)** — complete. Provides the Docker packaging, webhook controllers, HMAC filter, CJ adapter, and the base runbook this FR extends.
- **FR-028 (RAT-47, CJ API reconciliation)** — complete (PR #48 merged). Removed parse-drift as an outstanding CJ risk for this FR.
- **Shopify Partners account** — required; dev store is free, no subscription.
- **Stripe account with test-mode access** — required; `sk_test_` keys are free, no approval needed for test mode.
- **CJ Dropshipping account** — required. A **sandbox account** is strongly preferred (BR-3a); provisioning is non-self-serve (via CJ agent) and irreversible. A production account is acceptable only with the `dev-store-dry-run=true` kill-switch (BR-6b).
- **ngrok account** — required; reused from FR-029.
- **An operator (human)** — required to click through the hosted storefront checkout. This FR does not aim to automate the buyer-side browser flow.

---

## Out of Scope

- **Refund webhook handling** — owned by RAT-43. The test stops after fulfillment sync.
- **Observability upgrades** — owned by RAT-44. Log-based verification is sufficient for gate-zero.
- **Secrets-manager migration (Vault / 1Password / AWS SM)** — `.env` is the agreed posture for this milestone.
- **Multi-SKU concurrent listing test** — one SKU, one purchase is the bar.
- **Multi-buyer load testing** — one buyer, one order.
- **Real UPS/FedEx/USPS tracking poll integration** — documented as known gap in FR-029 runbook Section 10; the `DELIVERED` transition remains unreachable and that is accepted.
- **Capital module end-to-end** — `OrderFulfilled` event recording is gated behind `markDelivered()` which is unreachable; out of scope.
- **Live-mode guard in code** — explicitly rejected as brittle (Phase 1 decision); the runbook pre-flight audit is the mechanism.
- **Automated Playwright/headless-browser driven purchase** — manual buyer click-through is acceptable for gate-zero.

---

## Acceptance Criteria

The criteria below are the union of (a) RAT-53's verbatim ticket ACs and (b) the Phase 1 decisions restated as testable conditions.

### 7.1 From the RAT-53 ticket (verbatim)

- [ ] Shopify dev store created and publicly accessible at `*.myshopify.com`
- [ ] Admin API access token and webhook secret stored in secrets manager (not in code)
- [ ] Stripe test mode configured as payment provider
- [ ] App boots under `production` profile against dev store credentials without errors
- [ ] One product listed via automated pipeline (SKU LISTED → ShopifyListingAdapter → visible on storefront)
- [ ] One purchase completed end-to-end with dummy card `4242 4242 4242 4242`
- [ ] `orders/create` webhook received and internal Order created
- [ ] CJ order placement attempted
- [ ] Runbook updated with dev store setup steps

### 7.2 From Phase 1 decisions (additional, testable)

- [ ] **(Decision 1)** Stripe test mode is the configured payment provider on the dev store. Bogus Gateway is used only if explicitly documented in the run log as the fallback path with rationale.
- [ ] **(Decision 2)** `ShopifyInventoryCheckAdapter` no longer rejects the test SKU; either the adapter is fixed to resolve Shopify `inventory_item_id`, a mapping column has been added and seeded, or a documented feature flag disables the check for dev-store runs. Phase 3 picks the option; the gap is closed either way.
- [ ] **(Decision 3)** No Shopify/Stripe/CJ keys appear in any tracked git file. `.env` is gitignored and verified absent from the Docker image.
- [ ] **(Decision 4)** The test run exercises the real `LaunchReadySku → ShopifyListingAdapter` path — NOT a hand-inserted `platform_listings` row. If a new admin endpoint is introduced to trigger this path, it is gated behind a config property defaulting to disabled.
- [ ] **(Decision 5)** The runbook's new Section 0 "Pre-flight key audit" checklist exists and has been executed and signed off for the test run. No URL-pattern code guard is introduced.
- [ ] **(Decision 6)** Refunds are NOT attempted during this FR's test run; RAT-43 ownership is documented in the runbook.

### 7.3 From Non-Functional requirements (additional, testable)

- [ ] **(NFR-1)** ngrok inspector shows the Shopify `orders/create` response status 200 with total duration under 5 seconds.
- [ ] **(NFR-2)** The app's `STRIPE_SECRET_KEY` in the test environment starts with `sk_test_`, evidenced by the pre-flight audit record.
- [ ] **(BR-9)** Every webhook payload observed in ngrok during the test is saved under `docs/fixtures/shopify-dev-store/YYYY-MM-DD/` and committed to the repository.

---

## Open Questions for Phase 3

Surfaced during spec drafting; these are **not** gates on Phase 3 starting — they are decisions Phase 3 must make in the implementation plan:

1. **Inventory-check fix option (BR-6).** (a) feature flag, (b) DB mapping column, (c) live Shopify variant lookup. Phase 3 evaluates effort vs. long-term value; (a) is fastest, (c) is most correct.
2. **Listing-trigger surface (BR-5).** Does an existing endpoint suffice, or must Phase 3 add a gated admin endpoint? Answer determines whether this FR ships with new controller code at all.
3. **Archival automation (BR-9).** Pull from ngrok's `/inspect/http` HAR export vs. sniff at the webhook controller into a file appender. The second is more faithful (captures exactly what reaches our app); the first is easier.
4. **Pre-flight audit mechanism (BR-7).** A plain-text checklist in the runbook is sufficient; a Gradle task that echoes key prefixes to stdout would be a low-cost enhancement.
