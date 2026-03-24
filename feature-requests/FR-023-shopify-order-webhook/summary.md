# FR-023: Shopify Order Webhook — Summary

## Feature Summary

Implemented the Shopify order webhook listener for the fulfillment module. The system can now detect real customer purchases from Shopify's `orders/create` webhook, verify authenticity via HMAC-SHA256, resolve Shopify product IDs to internal SKUs, and create internal `Order` entities in PENDING status — closing the final gap to autonomous commerce. This is the go-live entry point that unblocks RAT-27 (supplier ordering), RAT-28 (shipment tracking), and RAT-29 (capital recording).

## Changes Made

### Security Layer
- `ShopifyHmacVerificationFilter` — servlet filter that verifies `X-Shopify-Hmac-SHA256` using constant-time comparison (`MessageDigest.isEqual()`). Supports multiple secrets for rotation. Runs before the controller — invalid signatures are rejected with 401 before any application logic executes.
- `CachingRequestWrapper` — `HttpServletRequestWrapper` that buffers raw request body bytes for HMAC computation while allowing re-reading by Spring's message converters.

### Handler Layer
- `ShopifyWebhookController` — `POST /webhooks/shopify/orders` endpoint. Validates `X-Shopify-Topic`, deduplicates via `X-Shopify-Event-Id`, optionally enforces replay protection, returns HTTP 200 immediately, publishes `ShopifyOrderReceivedEvent` for async processing.
- `ShopifyOrderReceivedEvent` — Spring ApplicationEvent carrying the raw payload and event ID.

### Domain Layer — Channel Order Abstraction
- `ChannelOrderAdapter` interface — `parse(rawPayload): ChannelOrder` + `channelName(): String`. Designed for multi-channel: Amazon, eBay, TikTok Shop can implement the same interface.
- `ChannelOrder` / `ChannelLineItem` — normalized order model independent of any channel's payload format.
- `ShopifyOrderAdapter` — first `ChannelOrderAdapter` implementation. Parses Shopify's `orders/create` JSON, extracts order ID, order number, customer email, currency, and line items. Uses Jackson `get()` (not `path()`) per CLAUDE.md constraint #15.

### Domain Layer — Processing Service
- `ShopifyOrderProcessingService` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` (constraint #6). Parses payload via adapter, resolves SKU IDs via `PlatformListingResolver`, resolves vendor IDs via `VendorSkuResolver`, creates one `Order` per resolvable line item via `OrderService.create()`, sets channel metadata. Handles partial resolution gracefully — unresolvable line items are logged and skipped.

### Proxy Layer — Cross-Module Resolvers
- `PlatformListingResolver` — native query against `platform_listings` table (follows capital module's `JpaActiveSkuProvider` pattern). Resolves Shopify product/variant IDs to internal SKU IDs without creating a module dependency on catalog.
- `VendorSkuResolver` — native query against `vendor_sku_assignments` table. Resolves SKU IDs to vendor IDs.

### Persistence Layer
- `WebhookEvent` entity — deduplication record with `eventId` (PK), `topic`, `channel`, `processedAt`.
- `WebhookEventRepository` — `existsByEventId()` for dedup, `deleteByProcessedAtBefore()` for TTL cleanup.
- `V20__webhook_events_and_order_channel.sql` — creates `webhook_events` table and adds `channel`, `channel_order_id`, `channel_order_number` columns to `orders` table.

### Order Entity Modifications
- Added `channel`, `channelOrderId`, `channelOrderNumber` nullable columns to `Order` for cross-reference with external sales channels.
- Added `OrderService.setChannelMetadata()` for post-creation channel data population.
- Updated `OrderResponse` and `OrderController.toResponse()` to expose channel fields.

### Configuration
- `ShopifyWebhookProperties` — `@ConfigurationProperties` with `secrets` list and `replayProtection` toggle.
- `ShopifyWebhookFilterConfig` — registers HMAC filter via `FilterRegistrationBean` on `/webhooks/shopify/*`.
- `WebhookEventCleanupJob` — `@Scheduled(cron = "0 0 3 * * *")` daily purge of events older than 24 hours.

## Files Modified

### New Source Files (19)
| File | Description |
|---|---|
| `.../handler/webhook/CachingRequestWrapper.kt` | Raw body caching for HMAC computation |
| `.../handler/webhook/ShopifyHmacVerificationFilter.kt` | HMAC-SHA256 verification filter with secret rotation |
| `.../handler/webhook/ShopifyWebhookController.kt` | Webhook endpoint with dedup + async dispatch |
| `.../handler/webhook/ShopifyOrderReceivedEvent.kt` | Spring ApplicationEvent for async processing |
| `.../domain/channel/ChannelOrderAdapter.kt` | Channel-agnostic adapter interface |
| `.../domain/channel/ChannelOrder.kt` | Normalized order data classes |
| `.../domain/channel/ShopifyOrderAdapter.kt` | Shopify payload parser |
| `.../domain/service/ShopifyOrderProcessingService.kt` | Async order creation from webhook events |
| `.../domain/service/WebhookEventCleanupJob.kt` | Scheduled TTL cleanup |
| `.../proxy/platform/PlatformListingResolver.kt` | Cross-module SKU resolution via native query |
| `.../proxy/platform/VendorSkuResolver.kt` | Cross-module vendor resolution via native query |
| `.../persistence/WebhookEvent.kt` | Deduplication JPA entity |
| `.../persistence/WebhookEventRepository.kt` | Dedup repository with TTL delete |
| `.../config/ShopifyWebhookProperties.kt` | Webhook configuration properties |
| `.../config/ShopifyWebhookFilterConfig.kt` | Filter registration |

### New Test Files (12)
| File | Tests |
|---|---|
| `.../handler/webhook/CachingRequestWrapperTest.kt` | 7 tests |
| `.../handler/webhook/ShopifyHmacVerificationFilterTest.kt` | 7 tests |
| `.../handler/webhook/ShopifyWebhookControllerTest.kt` | 5 tests |
| `.../handler/webhook/ShopifyWebhookIntegrationTest.kt` | 4 tests |
| `.../handler/webhook/ShopifyWebhookHmacIntegrationTest.kt` | 3 tests |
| `.../handler/webhook/ShopifyWebhookDeduplicationTest.kt` | 2 tests |
| `.../domain/channel/ShopifyOrderAdapterTest.kt` | 8 tests |
| `.../domain/service/ShopifyOrderProcessingServiceTest.kt` | 5 tests |
| `.../domain/service/WebhookEventCleanupJobTest.kt` | 2 tests |
| `.../proxy/platform/PlatformListingResolverTest.kt` | 3 tests |
| `.../proxy/platform/VendorSkuResolverTest.kt` | 2 tests |
| `.../domain/service/OrderServiceTest.kt` | 2 tests added |

### New Migration (1)
| File | Description |
|---|---|
| `modules/app/src/main/resources/db/migration/V20__webhook_events_and_order_channel.sql` | webhook_events table + order channel columns |

### Modified Files (6)
| File | Change |
|---|---|
| `modules/fulfillment/src/main/kotlin/.../domain/Order.kt` | Added channel, channelOrderId, channelOrderNumber fields |
| `modules/fulfillment/src/main/kotlin/.../domain/service/OrderService.kt` | Added setChannelMetadata() method |
| `modules/fulfillment/src/main/kotlin/.../handler/dto/OrderResponse.kt` | Added channel fields |
| `modules/fulfillment/src/main/kotlin/.../handler/OrderController.kt` | Updated toResponse() mapping |
| `modules/app/src/main/resources/application.yml` | Added shopify.webhook config section |
| `docs/e2e-test-playbook.md` | Added Phase 1b: Shopify Webhook Order Creation |

### Test Resource (1)
| File | Description |
|---|---|
| `modules/fulfillment/src/test/resources/shopify/orders-create-webhook.json` | Recorded Shopify webhook payload for tests |

## Testing Completed

- **50 new tests added**, all passing
- **445 total project tests**, all passing
- **Unit tests (41):** CachingRequestWrapper (7), ShopifyHmacVerificationFilter (7), ShopifyOrderAdapter (8), ShopifyOrderProcessingService (5), PlatformListingResolver (3), VendorSkuResolver (2), WebhookEventCleanupJob (2), OrderService setChannelMetadata (2), ShopifyWebhookController (5)
- **Integration tests (9):** ShopifyWebhookIntegration (4), ShopifyWebhookHmacIntegration (3), ShopifyWebhookDeduplication (2)
- **Full project build:** `./gradlew build` — BUILD SUCCESSFUL

## Deployment Notes

- **New environment variable:** `SHOPIFY_WEBHOOK_SECRETS` — comma-separated list of Shopify webhook HMAC secrets. Must be configured before the webhook endpoint is functional.
- **Optional config:** `shopify.webhook.replay-protection.enabled` (default: false) and `shopify.webhook.replay-protection.max-age-seconds` (default: 300)
- **Flyway migration V20** runs automatically on startup — creates `webhook_events` table and adds channel columns to `orders`
- **Webhook registration:** After deployment, register `POST /webhooks/shopify/orders` as a Shopify webhook endpoint via the Shopify Admin API or store notification settings
- **HTTPS required:** Shopify verifies SSL certificates when delivering webhooks
