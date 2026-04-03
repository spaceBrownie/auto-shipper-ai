# FR-026: CJ Tracking Webhook + Shopify Fulfillment Sync

## Problem Statement

When CJ Dropshipping ships a customer's order, it pushes a tracking webhook containing the tracking number and carrier name. The system currently has no endpoint to receive this webhook. Without it, orders placed via FR-025 sit in `CONFIRMED` status indefinitely -- the system knows a supplier order was placed but never learns that it shipped. Three downstream capabilities are blocked:

1. **Tracking activation.** The existing `ShipmentTracker` (FR-008) polls UPS/FedEx/USPS every 30 minutes and auto-detects delivery, but it only operates on `SHIPPED` orders with a tracking number. Without the CJ webhook, no order ever reaches `SHIPPED` and no tracking number is ever recorded -- the tracker has nothing to poll.

2. **Customer shipping notification.** Shopify sends the customer a native shipping confirmation email when a fulfillment with a tracking number is created on the order. Without pushing the tracking number to Shopify's Fulfillment API, the customer receives no shipping notification. In the zero-capital dropshipping model, this is the only mechanism for shipping communication -- there is no custom email system.

3. **Delivery detection and capital recording.** The chain is: `SHIPPED` -> `ShipmentTracker` polls carrier -> `DELIVERED` -> `OrderFulfilled` event -> capital reserve credited. This chain is already built (FR-008, FR-009) but cannot activate because no order ever reaches `SHIPPED`.

This feature closes the gap between "supplier order placed" (FR-025) and "shipment tracked to delivery" (FR-008). It is the final link in the autonomous order fulfillment pipeline: Shopify purchase -> internal order -> CJ supplier order -> **CJ ships and sends tracking** -> order marked shipped -> tracking pushed to Shopify -> carrier polling begins -> delivery detected -> capital recorded.

## Business Requirements

### BR-1: CJ Tracking Webhook Reception

The system must expose a `POST /webhooks/cj/tracking` endpoint that receives CJ Dropshipping's tracking notification webhook. The payload includes: `orderNumber` (our internal order UUID sent to CJ at placement time), `cjOrderId`, `orderStatus`, `logisticName` (carrier name), `trackNumber`, `createDate`, and `updateDate`. The endpoint must return HTTP 200 immediately to acknowledge receipt, then process asynchronously.

### BR-2: CJ Webhook Authentication

The system must verify inbound CJ webhooks using a shared secret token. CJ webhook authentication uses a token-based approach: the system must validate an authentication header or token parameter on inbound requests against a configured secret. Requests that fail authentication must be rejected with HTTP 401. The verification must occur at the servlet filter level (consistent with the Shopify HMAC filter pattern from FR-023) so unauthenticated requests never reach application code. The CJ webhook secret must be configurable via environment variable with an empty default per CLAUDE.md constraint #13.

### BR-3: Webhook Deduplication

CJ may deliver the same tracking notification multiple times (network retries, infrastructure replays). The system must deduplicate using a stable identifier derived from the webhook payload (e.g., `cj:{cjOrderId}:{trackNumber}`). Deduplication must use the existing `WebhookEvent` entity and `WebhookEventPersister` (created in FR-023) with `channel = "cj"`. Duplicate webhooks must receive HTTP 200 without re-processing.

### BR-4: Order Matching via Order Number

The CJ webhook's `orderNumber` field contains our internal `Order.id` (UUID), which was sent to CJ as the `orderNumber` parameter in `CjSupplierOrderAdapter.placeOrder()` (FR-025). The system must parse this UUID and look up the corresponding internal order via `OrderRepository.findById()`. If the order is not found or is not in `CONFIRMED` status, the webhook must be logged as a warning and acknowledged with HTTP 200 (do not reject -- CJ would retry).

### BR-5: Order Transition to SHIPPED with Tracking

When the CJ webhook is received and the order is matched, the system must call `OrderService.markShipped(orderId, trackingNumber, carrier)` to:
- Transition the order from `CONFIRMED` to `SHIPPED`
- Record the tracking number from the `trackNumber` field
- Record the carrier name from the `logisticName` field (after normalization -- see BR-6)

This transition must publish an `OrderShipped` domain event (see BR-7) so downstream listeners can react.

### BR-6: Carrier Name Normalization

CJ's `logisticName` field contains free-text carrier names (e.g., "USPS", "UPS", "FedEx", "4PX", "YunExpress", "Yanwen"). The existing `ShipmentTracker` resolves carriers by matching `CarrierTrackingProvider.carrierName` (lowercase). The system must normalize CJ's logistic names to match the carrier names used by the tracking providers. This requires a mapping from CJ logistic names to internal carrier names. Unknown carrier names must be stored as-is on the order (tracking can still display to customers via Shopify even if the `ShipmentTracker` cannot poll that carrier).

### BR-7: OrderShipped Domain Event

A new `OrderShipped` domain event must be created in the shared module, following the pattern of `OrderConfirmed` and `OrderFulfilled`. It must carry `orderId`, `skuId`, `trackingNumber`, and `carrier`. This event enables cross-module reactions to shipment -- specifically, the Shopify fulfillment sync listener (BR-8) must react to this event, not be called directly from the webhook handler.

### BR-8: Shopify Fulfillment Sync

When an `OrderShipped` event is published, the system must push the tracking number and carrier to Shopify by creating a fulfillment on the Shopify order. This must use Shopify's Admin GraphQL API (`fulfillmentCreateV2` mutation) to:
- Create a fulfillment on the Shopify order identified by `Order.channelOrderId`
- Include the tracking number and carrier company name
- Set `notifyCustomer: true` so Shopify sends the native shipping confirmation email

The listener must follow CLAUDE.md constraint #6: `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`. This ensures the order's `SHIPPED` status is committed before the Shopify API call is attempted, and a Shopify API failure does not roll back the shipment status.

### BR-9: Shopify Fulfillment API Integration

The Shopify fulfillment sync must use the existing `shopifyRestClient` bean (configured with base URL and access token in `application.yml`) to POST a GraphQL mutation to `/admin/api/2024-01/graphql.json`. No new HTTP client dependencies are needed. The request must include the `X-Shopify-Access-Token` header. Shopify API errors must be logged but must not fail the order -- the order is already `SHIPPED` regardless of whether Shopify was notified. A retry mechanism (Resilience4j `@Retry`) should handle transient Shopify failures.

### BR-10: Existing ShipmentTracker Activation

No changes to `ShipmentTracker` are required. Once `OrderService.markShipped()` records the tracking number and carrier on the order and transitions it to `SHIPPED`, the existing `ShipmentTracker.pollAllShipments()` (which runs every 30 minutes) will automatically pick it up and begin polling the carrier API. When the carrier confirms delivery, the existing chain fires: `DELIVERED` -> `OrderFulfilled` event -> capital record credited.

### BR-11: Shopify Order ID Requirement

The Shopify fulfillment sync requires the Shopify order's global ID (GID) to create a fulfillment. The `Order.channelOrderId` field (added in FR-023) stores the Shopify order ID. For orders where `channelOrderId` is null (orders not originating from a Shopify webhook, or legacy orders), the Shopify fulfillment sync must skip silently with a warning log rather than fail.

## Success Criteria

### SC-1: CJ Webhook Endpoint Operational
A `POST /webhooks/cj/tracking` endpoint exists, is protected by token-based authentication at the filter level, and returns HTTP 200 for valid webhook payloads.

### SC-2: Order Matched and Shipped
When the CJ webhook contains a valid `orderNumber` (internal order UUID) for an order in `CONFIRMED` status, the order transitions to `SHIPPED` with the tracking number and normalized carrier name recorded on `ShipmentDetails`.

### SC-3: OrderShipped Event Published
`OrderService.markShipped()` publishes an `OrderShipped` domain event containing `orderId`, `skuId`, `trackingNumber`, and `carrier` after the `SHIPPED` transition commits.

### SC-4: Shopify Fulfillment Created
An `@TransactionalEventListener(AFTER_COMMIT)` handler consumes the `OrderShipped` event and calls the Shopify Admin GraphQL API to create a fulfillment with the tracking number and carrier. The fulfillment request includes `notifyCustomer: true`.

### SC-5: Customer Receives Shipping Email
With `notifyCustomer: true` on the Shopify fulfillment, Shopify automatically sends the customer its native shipping confirmation email with tracking information. No custom email infrastructure is needed.

### SC-6: ShipmentTracker Auto-Activates
After `markShipped()`, the order appears in `orderRepository.findByStatus(OrderStatus.SHIPPED)` with a non-null tracking number and carrier. The existing `ShipmentTracker.pollAllShipments()` picks it up on its next 30-minute cycle.

### SC-7: Delivery Completes the Chain
When `ShipmentTracker` detects delivery from the carrier API, the existing `markDelivered()` -> `OrderFulfilled` event -> capital record chain fires without any new code.

### SC-8: Deduplication Works
A duplicate CJ webhook (same `cjOrderId` + `trackNumber`) returns HTTP 200 without re-processing the order or calling Shopify again.

### SC-9: WireMock Test for CJ Webhook
A WireMock-backed test exercises the CJ webhook endpoint with a realistic payload (based on CJ API documentation, not reverse-engineered). The test verifies: order matched, status transitioned to `SHIPPED`, tracking number and carrier recorded.

### SC-10: Integration Test for Full Chain
An integration test verifies: CJ webhook received -> order matched -> `SHIPPED` -> `OrderShipped` event -> Shopify fulfillment API called (WireMock) -> correct GraphQL mutation with tracking number, carrier, and `notifyCustomer: true`.

### SC-11: Graceful Handling of Edge Cases
- Webhook with unknown `orderNumber`: logged, HTTP 200 returned
- Webhook for order not in `CONFIRMED` status: logged, HTTP 200 returned
- Webhook with missing `trackNumber`: logged, HTTP 200 returned (do not transition)
- Shopify API failure: logged, order remains `SHIPPED` (tracking still active via carrier polling)
- Order without `channelOrderId`: Shopify sync skipped with warning log

## Non-Functional Requirements

### NFR-1: Transaction Safety (CLAUDE.md #6)
The `OrderShipped` event listener that calls the Shopify Fulfillment API must use `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`. The `SHIPPED` status must be committed before Shopify is called. A Shopify failure must not roll back the shipment. The CJ webhook processing itself should also follow the async event pattern: the controller deduplicates and acknowledges, then publishes an internal event for processing in an AFTER_COMMIT listener.

### NFR-2: JSON Parsing Safety (CLAUDE.md #15, #17)
All Jackson `get()` calls on the CJ webhook JSON payload must use:
- `get()` not `path()` for fields where absence should coalesce to null
- The NullNode guard `?.let { if (!it.isNull) it.asText() else null }` on every field
- Consistent application across all fields in the same parsing block

This is the same constraint that caught the PR #39 bug on shipping address fields.

### NFR-3: Credential Guard (CLAUDE.md #13)
The CJ webhook secret configuration must use `@Value` with empty default (`${key:}`). If the secret is blank, the verification filter must reject all requests (same pattern as `ShopifyHmacVerificationFilter` when no secrets are configured).

### NFR-4: No Internal Constructors (CLAUDE.md #14)
No `@Component`/`@Service`/`@Repository` classes may use `internal` constructors. Spring cannot resolve the synthetic `DefaultConstructorMarker` parameter at runtime.

### NFR-5: Assigned ID Entities (CLAUDE.md #16)
Any new JPA entities with assigned (non-generated) `@Id` must implement `Persistable<T>` with `@Transient isNew` field and `@PostPersist`/`@PostLoad` to flip it. The `WebhookEvent` entity already follows this pattern and is reused.

### NFR-6: WireMock Fixture Accuracy
WireMock fixtures for the CJ webhook payload must be based on the CJ API documentation at `https://developers.cjdropshipping.com/`, not reverse-engineered from handler code. The Shopify GraphQL fulfillment response fixture must be based on Shopify's Admin GraphQL API documentation.

### NFR-7: No Test Theater
Every test must call production code and assert on its output. No `assert(true)`, no fixture content assertions, no constructor round-trip tests. The CJ webhook test must exercise the full handler path: JSON parsing -> order lookup -> status transition -> event publication. The Shopify fulfillment test must verify the GraphQL mutation body contains the correct tracking number, carrier, and `notifyCustomer` flag.

### NFR-8: Resilience
The Shopify fulfillment sync adapter must use `@Retry` (Resilience4j) for transient API failures. The CJ webhook handler itself does not need resilience annotations (CJ controls retry behavior on their side). Circuit breaker on Shopify fulfillment sync is optional but recommended.

### NFR-9: Observability
- CJ webhook received: log at INFO with `orderNumber` and `trackNumber`
- Order matched and shipped: log at INFO with `orderId`, `trackingNumber`, `carrier`
- Shopify fulfillment created: log at INFO with `orderId` and Shopify fulfillment ID
- Shopify fulfillment failed: log at WARN with `orderId` and error details
- Unknown carrier name: log at WARN with original `logisticName` value
- Order not found or wrong status: log at WARN with `orderNumber` and current status

### NFR-10: Configuration
New configuration required in `application.yml`:
- `cj-dropshipping.webhook.secret` (env: `CJ_WEBHOOK_SECRET`) -- token for webhook authentication
- Shopify API credentials are already configured (`shopify.api.base-url`, `shopify.api.access-token`)

### NFR-11: Flyway Migration
If any schema changes are needed (e.g., a carrier name mapping table), they must be delivered as a Flyway migration. The current highest migration is V21. The `OrderShipped` event does not require schema changes -- it is a Spring ApplicationEvent. The carrier mapping can be implemented in code (a static map) rather than a database table for Phase 1, since the set of carriers CJ uses is small and stable.

## Dependencies

- **FR-008** (fulfillment-orchestration) -- `Order` entity, `OrderService.markShipped()`, `ShipmentTracker`, `ShipmentDetails`, `CarrierTrackingProvider` interface, `OrderStatus` enum, `OrderRepository`
- **FR-009** (capital-protection) -- `OrderFulfilled` event listener that credits capital reserve on delivery (already built, activates automatically)
- **FR-023** (shopify-order-webhook) -- `WebhookEvent` entity, `WebhookEventPersister`, `WebhookEventRepository`, `CachingRequestWrapper`, `ShopifyHmacVerificationFilter` (pattern reference), `ShopifyWebhookController` (pattern reference), `Order.channelOrderId` field
- **FR-025** (cj-supplier-order-placement) -- `CjSupplierOrderAdapter` (sends `orderId.toString()` as `orderNumber` to CJ), `SupplierOrderAdapter` interface, CJ API configuration in `application.yml`
- **Shared module** -- `DomainEvent` interface, `OrderId`, `SkuId` identity types
- **CJ Dropshipping Webhook API** -- tracking notification push when orders ship; registered via `POST /webhook/set` on CJ API
- **Shopify Admin GraphQL API** -- `fulfillmentCreateV2` mutation for creating fulfillments with tracking numbers; existing `shopify.api` credentials provide access
