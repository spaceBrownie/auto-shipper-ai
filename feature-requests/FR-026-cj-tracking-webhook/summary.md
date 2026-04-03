# FR-026: CJ Tracking Webhook + Shopify Fulfillment Sync -- Summary

## What Was Built

Automatic tracking number ingestion from CJ Dropshipping via webhook, with Shopify fulfillment sync to trigger native customer shipping notification emails. This closes the gap between "supplier order placed" (FR-025) and "shipment tracked to delivery" (FR-008).

**End-to-end flow:**
```
CJ ships order
  -> POST /webhooks/cj/tracking (token-verified)
  -> Dedup via WebhookEventPersister (channel = "cj")
  -> HTTP 200 returned immediately
  -> CjTrackingProcessingService (AFTER_COMMIT + REQUIRES_NEW)
  -> Parse payload, match order by UUID, normalize carrier
  -> OrderService.markShipped() -> publishes OrderShipped event
  -> ShopifyFulfillmentSyncListener (AFTER_COMMIT + REQUIRES_NEW)
  -> ShopifyFulfillmentAdapter calls fulfillmentCreateV2 GraphQL mutation
  -> Shopify sends native shipping confirmation email to customer
  -> ShipmentTracker auto-polls carrier on next 30-min cycle
  -> Delivery detected -> OrderFulfilled -> capital record created
```

## Files Created

### Production Code (11 new files, 2 modified)

| File | Purpose |
|------|---------|
| `shared/.../events/OrderShipped.kt` | New domain event: orderId, skuId, trackingNumber, carrier |
| `fulfillment/.../handler/webhook/CjTrackingWebhookController.kt` | POST endpoint, dedup, publish internal event |
| `fulfillment/.../handler/webhook/CjTrackingReceivedEvent.kt` | Internal Spring event bridging controller to processing service |
| `fulfillment/.../handler/webhook/CjWebhookTokenVerificationFilter.kt` | Servlet filter with constant-time token comparison |
| `fulfillment/.../domain/service/CjTrackingProcessingService.kt` | AFTER_COMMIT listener: parse, match, markShipped |
| `fulfillment/.../proxy/carrier/CjCarrierMapper.kt` | Static map normalizing CJ carrier names to internal names |
| `fulfillment/.../proxy/platform/ShopifyFulfillmentPort.kt` | Interface for fulfillment sync |
| `fulfillment/.../proxy/platform/ShopifyFulfillmentAdapter.kt` | Real impl: GraphQL fulfillmentCreateV2 mutation |
| `fulfillment/.../proxy/platform/StubShopifyFulfillmentAdapter.kt` | Local profile stub |
| `fulfillment/.../handler/ShopifyFulfillmentSyncListener.kt` | AFTER_COMMIT listener on OrderShipped |
| `fulfillment/.../config/CjWebhookProperties.kt` | Config properties for webhook secret |
| `fulfillment/.../config/CjWebhookFilterConfig.kt` | FilterRegistrationBean for CJ webhook URL pattern |
| `fulfillment/.../domain/service/OrderService.kt` | **Modified**: markShipped() now publishes OrderShipped event |
| `app/.../application.yml` | **Modified**: CJ webhook secret + Shopify fulfillment resilience4j config |

### Test Code (8 test files, 60 tests)

| Test File | Tests | Coverage |
|-----------|-------|----------|
| `CjCarrierMapperTest` | 14 | All 9 carrier mappings, unknown pass-through, case insensitivity |
| `CjTrackingProcessingServiceTest` | 12 | Happy path, all edge cases, NullNode guards for every field |
| `CjWebhookTokenVerificationFilterTest` | 8 | Valid/invalid token, blank secret, Bearer prefix, CachingRequestWrapper |
| `CjTrackingWebhookControllerTest` | 8 | Valid webhook, dedup paths, missing fields, dedup key format |
| `ShopifyFulfillmentAdapterWireMockTest` | 8 | GraphQL mutation body, headers, error responses, blank token |
| `ShopifyFulfillmentSyncListenerTest` | 5 | Happy path, missing channelOrderId, exception swallowing |
| `CjTrackingWebhookIntegrationTest` | 3 | Full chain, missing channelOrderId, Shopify failure resilience |
| `OrderServiceTest` (updated) | +2 | OrderShipped event publication with correct fields |

### WireMock Fixtures (4 files)

- `fulfillment-create-success.json` -- Shopify GraphQL success response
- `fulfillment-create-user-errors.json` -- Shopify validation error
- `fulfillment-create-auth-error.json` -- Shopify 401 response
- `fulfillment-create-null-fulfillment.json` -- Null fulfillment with empty userErrors

## Key Design Decisions

1. **Two-phase processing** -- Controller deduplicates and returns 200 immediately; AFTER_COMMIT listener does heavy work. Matches ShopifyWebhookController pattern.

2. **Token-based auth** (not HMAC) -- CJ uses a simple Bearer token, not HMAC signatures. Filter rejects all when secret is blank (CLAUDE.md #13).

3. **Static carrier mapping** -- CjCarrierMapper is a Kotlin object with 9 known carriers. Unknown carriers pass through as-is; ShipmentTracker gracefully skips them.

4. **OrderShipped published from OrderService.markShipped()** -- Not from the webhook handler. Any future caller of markShipped() also triggers Shopify sync.

5. **Shopify failures are non-fatal** -- ShopifyFulfillmentSyncListener catches all exceptions. Order remains SHIPPED regardless of Shopify API status.

6. **No Flyway migration** -- All needed schema already exists from V20 (webhook_events) and V21 (order tracking columns).

## CLAUDE.md Constraints Enforced

| # | Constraint | Where |
|---|-----------|-------|
| 6 | AFTER_COMMIT + REQUIRES_NEW | CjTrackingProcessingService, ShopifyFulfillmentSyncListener |
| 13 | @Value empty defaults | CjWebhookProperties, ShopifyFulfillmentAdapter |
| 14 | No internal constructors | All @Component classes use public constructors |
| 15 | get() not path() | All JSON parsing in controller and processing service |
| 17 | NullNode guard | Every external JSON field extraction |

## Acceptance Criteria Status

- [x] CJ tracking webhook received and order matched
- [x] Order transitions to SHIPPED with correct tracking number and carrier
- [x] Tracking number pushed to Shopify fulfillment API (fulfillmentCreateV2 GraphQL)
- [x] Customer receives Shopify's native shipping confirmation email (notifyCustomer: true)
- [x] Existing ShipmentTracker starts polling the carrier automatically
- [x] Delivery auto-detected -> OrderFulfilled -> capital record created (existing, no changes)
- [x] WireMock test for CJ webhook payload
- [x] Integration test for full tracking -> fulfillment sync chain
