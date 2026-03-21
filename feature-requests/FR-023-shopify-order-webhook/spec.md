# FR-023: Shopify Order Webhook

## Problem Statement

The system can discover demand signals, verify costs, stress-test margins, set prices, pass compliance checks, and create product listings on Shopify (FR-020) -- but it cannot detect when a customer buys something. When a Shopify customer completes checkout, Shopify sends an `orders/create` webhook to a registered endpoint. No such endpoint exists. Without it, every downstream operation -- supplier ordering (RAT-27), shipment tracking (RAT-28), and capital recording (RAT-29) -- requires manual API polling or human intervention, violating the system's autonomous operation mandate.

This is the go-live entry point. The system currently produces listings but is deaf to sales. A product can sit on Shopify with completed purchases accumulating while the system remains unaware, unable to route orders to vendors, unable to track shipments, and unable to record revenue against the capital reserve. RAT-27, RAT-28, and RAT-29 all depend on an internal `Order` existing in PENDING status -- and this feature is the only path to creating that order from a real customer purchase.

The `Order` entity, `OrderService.create(CreateOrderCommand)`, and the order state machine (PENDING -> CONFIRMED -> SHIPPED -> DELIVERED -> REFUNDED/RETURNED) already exist in the fulfillment module. The `platform_listings` table (FR-020) maps internal `SkuId` values to Shopify product IDs and variant IDs. What is missing is the inbound webhook handler that receives Shopify's order payload, verifies its authenticity, resolves Shopify product/variant IDs to internal SKU and vendor IDs, and delegates to `OrderService.create()`.

## Business Requirements

### BR-1: Webhook reception and authentication

The system must expose a `POST /webhooks/shopify/orders` endpoint that receives Shopify's `orders/create` webhook payload. Every incoming request must be authenticated by verifying the `X-Shopify-Hmac-SHA256` header against the configured webhook shared secret using HMAC-SHA256 with Base64 encoding. Verification must occur at the servlet filter level -- before the request reaches the controller -- and must use constant-time comparison (`MessageDigest.isEqual()`) to prevent timing attacks. Requests that fail HMAC verification must be rejected with HTTP 401 and must not be processed further.

### BR-2: Secret rotation support

The system must support a list of valid webhook secrets (not just a single secret) so that during secret rotation, webhooks signed with either the old or new secret are accepted. HMAC verification succeeds if the computed digest matches the header value for any secret in the configured list.

### BR-3: Topic and replay validation

The system must validate the `X-Shopify-Topic` header to confirm it matches `orders/create` before processing. The system should optionally support replay protection by rejecting webhooks where the `X-Shopify-Triggered-At` timestamp is older than a configurable threshold (default: 5 minutes). Replay protection should be toggleable via configuration since clock skew may cause false rejections in some environments.

### BR-4: Async processing with immediate acknowledgment

The system must return HTTP 200 immediately after HMAC verification and event deduplication, then process the order asynchronously. Shopify enforces a 5-second timeout on webhook responses and removes the webhook subscription after 8 consecutive failed deliveries over 4 hours. Synchronous processing risks timeout on downstream operations (SKU resolution, inventory checks, order persistence), which would cause Shopify to retry and eventually unsubscribe.

### BR-5: Event-level idempotency

The system must track the `X-Shopify-Event-Id` header to deduplicate webhook deliveries at the event level. Shopify may deliver the same event multiple times (retries on timeout, infrastructure replays). The same Shopify order can also trigger multiple distinct events (e.g., `orders/create` followed by `orders/updated`). Deduplication must operate on event ID, not order ID, to correctly distinguish retries from distinct events. Duplicate events must receive HTTP 200 (acknowledged) without creating duplicate orders.

### BR-6: SKU and vendor resolution

The system must resolve Shopify line items to internal SKU IDs and vendor IDs. Each line item in the Shopify order payload contains a `product_id` and `variant_id`. The `platform_listings` table (created by FR-020) maps `external_listing_id` (Shopify product ID) and `external_variant_id` (Shopify variant ID) to `sku_id`. The vendor ID must be resolved from the SKU's associated vendor record. Line items that cannot be resolved to a known SKU must be logged as warnings and skipped -- they may represent manually-created Shopify products not managed by the system.

### BR-7: Internal order creation

For each resolvable line item, the system must create an internal `Order` entity in PENDING status via `OrderService.create(CreateOrderCommand)`. The `CreateOrderCommand` must be populated as follows:

- `skuId`: resolved from `platform_listings` via the Shopify product/variant ID
- `vendorId`: resolved from the SKU's vendor association
- `customerId`: derived from the Shopify customer object (a new or existing internal customer record)
- `totalAmount`: the line item price multiplied by quantity, expressed as `Money` with the order's currency
- `paymentIntentId`: the Shopify order's `payment_gateway_names[0]` combined with the Shopify order ID (e.g., `shopify:order:12345`) since Shopify does not expose a Stripe-style payment intent
- `idempotencyKey`: constructed from the Shopify order ID and line item index (e.g., `shopify:order:12345:item:0`) to ensure one internal order per line item per Shopify order

### BR-8: Shopify order cross-reference

The system must store the Shopify order ID, Shopify order number (human-readable `#1001` format), and the originating channel identifier (`shopify`) alongside the internal order for cross-referencing. This enables customer support lookups, reconciliation with Shopify's order admin, and downstream fulfillment updates that reference the original Shopify order.

### BR-9: Channel-agnostic adapter interface

The webhook handler must be designed around a `ChannelOrderAdapter` interface so that future sales channels (Amazon SP-API, eBay, TikTok Shop) can be added by implementing the same interface without modifying the core order creation flow. The interface must define: (a) a method to parse a channel-specific payload into a normalized `ChannelOrder` representation, and (b) a method to acknowledge receipt to the channel. The Shopify implementation is the first adapter.

### BR-10: Multi-line-item order handling

A single Shopify order may contain multiple line items spanning different SKUs from different vendors. Each resolvable line item must result in a separate internal `Order` entity, since the fulfillment module routes orders to individual vendors. The system must handle partial resolution gracefully -- if 3 of 4 line items resolve to known SKUs, 3 orders are created and the unresolvable item is logged.

## Success Criteria

- [ ] `POST /webhooks/shopify/orders` endpoint exists and is reachable
- [ ] HMAC-SHA256 verification occurs at the servlet filter level using `MessageDigest.isEqual()` for constant-time comparison; requests with invalid signatures receive HTTP 401
- [ ] Secret rotation is supported: HMAC verification succeeds against any secret in a configured list
- [ ] `X-Shopify-Topic` header is validated to match `orders/create`; mismatched topics receive HTTP 400
- [ ] `X-Shopify-Event-Id` is tracked for deduplication; duplicate events receive HTTP 200 without creating duplicate orders
- [ ] HTTP 200 is returned within Shopify's 5-second timeout window; order processing continues asynchronously
- [ ] Shopify line items are resolved to internal SKU IDs via the `platform_listings` table (`external_listing_id` / `external_variant_id` -> `sku_id`)
- [ ] Vendor ID is resolved from the SKU's vendor association for each line item
- [ ] An internal `Order` entity is created in PENDING status for each resolvable line item, with correct `skuId`, `vendorId`, `totalAmount`, `paymentIntentId`, and `idempotencyKey`
- [ ] Idempotent: reprocessing the same Shopify order (same idempotency key) returns the existing `Order` without creating a duplicate, leveraging `OrderService.create()`'s existing idempotency logic
- [ ] Shopify order ID and order number are stored for cross-reference on the internal order
- [ ] Unresolvable line items (no matching `platform_listings` record) are logged at WARN level and skipped without failing the entire webhook
- [ ] Multi-line-item orders produce one internal `Order` per resolvable line item
- [ ] `ChannelOrderAdapter` interface is defined with parse and acknowledge operations
- [ ] `ShopifyOrderAdapter` implements `ChannelOrderAdapter`
- [ ] Integration test exercises the full webhook flow using a recorded Shopify `orders/create` payload with valid HMAC
- [ ] Integration test verifies HMAC rejection with an invalid signature
- [ ] Integration test verifies event-level deduplication
- [ ] `./gradlew build` passes with all new tests

## Non-Functional Requirements

### NFR-1: Security -- HMAC verification at filter level

HMAC verification must execute in a custom servlet filter, not in the controller. The filter must use a `CachingRequestWrapper` (or equivalent) to buffer the raw request body bytes for HMAC computation while still allowing the body to be read downstream by Spring's message converters. The raw body bytes -- not a deserialized-then-reserialized representation -- must be used for HMAC computation, since any transformation (whitespace normalization, field reordering) would invalidate the signature.

### NFR-2: Security -- constant-time comparison

HMAC digest comparison must use `java.security.MessageDigest.isEqual()` to prevent timing side-channel attacks. String equality (`==`, `.equals()`) is prohibited for HMAC comparison.

### NFR-3: Security -- no IP allowlisting dependency

Shopify uses dynamic IP addresses for webhook delivery. The system must not depend on IP allowlisting for security. HMAC verification is the sole authentication mechanism.

### NFR-4: Performance -- async processing within timeout

The webhook endpoint must return HTTP 200 within 5 seconds (Shopify's timeout). All downstream processing (SKU resolution, inventory checks, order persistence, event publishing) must occur asynchronously after the HTTP response is sent. This prevents Shopify from retrying or unsubscribing the webhook due to timeouts.

### NFR-5: Reliability -- replay protection

Replay protection (rejecting webhooks with `X-Shopify-Triggered-At` older than a configurable threshold) must be optional and disabled by default. When enabled, the threshold must be configurable (default: 5 minutes). Clock skew between Shopify's servers and the application server can cause false rejections, so operators must be able to disable this guard.

### NFR-6: Reliability -- event deduplication storage

Event ID deduplication records must have a TTL or scheduled cleanup to prevent unbounded growth. Shopify's retry window is 4 hours; deduplication records older than 24 hours can be safely purged. The deduplication store must survive application restarts (database-backed, not in-memory).

### NFR-7: Observability

The following must be logged at INFO level: webhook received (with Shopify order ID), HMAC verification result, event deduplication decision, SKU resolution results (resolved vs. unresolvable), and order creation outcome. HMAC verification failures must be logged at WARN level with the request's remote address (but never the request body or HMAC header value, which are sensitive).

### NFR-8: Configuration

The webhook shared secret(s) must be configured via environment variables following the established pattern (e.g., `SHOPIFY_WEBHOOK_SECRET`). Multiple secrets for rotation must be supported via a comma-separated value or list syntax. The replay protection threshold and toggle must be configurable via `application.yml` properties.

### NFR-9: Adapter constraints

The `ShopifyOrderAdapter` (and any future `ChannelOrderAdapter` implementations) must follow all CLAUDE.md engineering constraints:
- No `internal` constructor on `@Component` classes (constraint #14)
- `@Value` annotations must use `${key:}` empty-default syntax (constraint #13)
- URL-encode any user-supplied values in outbound request bodies (constraint #12)
- Use `Jackson get()` instead of `path()` when absence should trigger `?: null` coalescing (constraint #15)
- XML parsing (if any) must use `SecureXmlFactory` (constraint #11)

### NFR-10: Resilience

The async order processing pipeline must handle transient database failures gracefully. If `OrderService.create()` fails due to a transient error, the processing must be retryable without requiring Shopify to redeliver the webhook. A failed processing attempt must be logged at ERROR level and queued for retry.

## Dependencies

### Upstream dependencies (this feature depends on)

| Dependency | Module | Reason |
|---|---|---|
| `Order` entity and state machine | `fulfillment` | Target entity for order creation; provides PENDING status and idempotency key support |
| `OrderService.create(CreateOrderCommand)` | `fulfillment` | Existing service method with idempotency logic (`findByIdempotencyKey()`) and inventory validation |
| `CreateOrderCommand` | `fulfillment` | Command object defining required fields: skuId, vendorId, customerId, totalAmount, paymentIntentId, idempotencyKey |
| `InventoryChecker` | `fulfillment` | Called by `OrderService.create()` to validate SKU availability before order creation |
| `PlatformListingEntity` / `PlatformListingRepository` | `catalog` | Provides the Shopify product ID -> internal SKU ID mapping via `findBySkuIdAndPlatform()` (needs reverse lookup: find by `externalListingId` and `platform`) |
| `platform_listings` table (V19 migration) | `app` | Database schema for platform listing records |
| Shopify API configuration | `app` | Existing `shopify.api.base-url` and `shopify.api.access-token` in `application.yml`; webhook secret must be added |
| FR-020 (platform-listing-adapter) | `catalog` | Must be implemented first so that SKU listings exist in `platform_listings` when orders arrive |
| FR-022 (integration-test-coverage-gaps) | `app` | Order transition REST endpoints (`POST /api/orders/{id}/confirm`, etc.) enable E2E testing of the order lifecycle triggered by this webhook |

### Downstream dependents (depend on this feature)

| Dependent | Ticket | Reason |
|---|---|---|
| Supplier auto-ordering | RAT-27 | Needs an internal `Order` in PENDING status to trigger vendor purchase order creation |
| Shipment tracking pipeline | RAT-28 | Needs an internal `Order` to associate tracking numbers and delivery status with |
| Capital recording and reserve crediting | RAT-29 | Needs order revenue data to credit the capital reserve and compute margin snapshots |
| E2E go-live playbook | -- | The webhook is the entry point for the full autonomous commerce loop: sale -> order -> supplier -> shipment -> delivery -> capital |

### New infrastructure requirements

| Requirement | Details |
|---|---|
| `shopify.api.webhook-secret` configuration property | New property in `application.yml` for the Shopify webhook HMAC shared secret; must support comma-separated values for rotation |
| `shopify.webhook.replay-protection.enabled` | Boolean toggle for replay protection (default: `false`) |
| `shopify.webhook.replay-protection.max-age-seconds` | Configurable replay window (default: `300`) |
| Webhook event deduplication table | Database table to track processed `X-Shopify-Event-Id` values with TTL for cleanup |
| Reverse lookup on `platform_listings` | `PlatformListingRepository` needs a query method to find by `externalListingId` and `platform` (currently only supports lookup by `skuId`) |
