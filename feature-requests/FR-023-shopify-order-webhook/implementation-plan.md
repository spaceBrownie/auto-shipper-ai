# FR-023: Shopify Order Webhook — Implementation Plan

## Technical Design

### Architecture Overview

FR-023 closes the final gap to autonomous commerce: detecting real customer purchases. When a Shopify customer completes checkout, Shopify sends an `orders/create` webhook. This feature receives that webhook, verifies its authenticity, resolves Shopify product IDs to internal SKU/vendor IDs, and creates internal `Order` entities in PENDING status via the existing `OrderService.create()`.

```
Shopify Storefront
       |
       | POST /webhooks/shopify/orders
       | X-Shopify-Hmac-SHA256, X-Shopify-Event-Id, X-Shopify-Topic
       v
  ShopifyHmacVerificationFilter (servlet filter)
       |
       | HMAC valid? ── NO ──> HTTP 401 (logged at WARN)
       | YES
       v
  ShopifyWebhookController
       |
       | 1. Validate X-Shopify-Topic == "orders/create"
       | 2. Deduplicate via X-Shopify-Event-Id (WebhookEventRepository)
       | 3. Optional replay protection via X-Shopify-Triggered-At
       | 4. Return HTTP 200 immediately
       | 5. Publish ShopifyOrderReceivedEvent (Spring ApplicationEvent)
       v
  ShopifyOrderProcessingService  (@TransactionalEventListener AFTER_COMMIT + REQUIRES_NEW)
       |
       | 1. Parse payload via ShopifyOrderAdapter (ChannelOrderAdapter impl)
       | 2. Resolve SKU IDs via PlatformListingResolver (native query on platform_listings)
       | 3. Resolve vendor IDs via VendorSkuResolver (native query on vendor_sku_assignments)
       | 4. For each resolvable line item: OrderService.create(CreateOrderCommand)
       v
  OrderService.create()  (existing — idempotency, inventory check, persist)
       |
       v
  Order (PENDING) ──> downstream: RAT-27 supplier ordering, RAT-28 shipment tracking, RAT-29 capital
```

### How the Filter Intercepts and Verifies HMAC

The `ShopifyHmacVerificationFilter` is a `jakarta.servlet.Filter` registered via `FilterRegistrationBean` and mapped to `/webhooks/shopify/*`. It:

1. Wraps the `HttpServletRequest` in a `CachingRequestWrapper` that buffers the raw body bytes on first read, allowing them to be re-read by Spring's message converters downstream.
2. Reads the raw body bytes (not deserialized-then-reserialized) for HMAC computation.
3. Computes HMAC-SHA256 over the raw bytes for each configured secret, Base64-encodes the result, and compares against the `X-Shopify-Hmac-SHA256` header using `MessageDigest.isEqual()` (constant-time).
4. If any secret produces a matching digest, the filter chains the request through. Otherwise, it returns HTTP 401.

This runs before the controller — invalid signatures never reach application code.

### How Async Processing Works

The controller returns HTTP 200 immediately after HMAC verification, topic validation, and event deduplication. It then publishes a `ShopifyOrderReceivedEvent` (a Spring `ApplicationEvent`) containing the raw JSON payload string and the Shopify event ID.

`ShopifyOrderProcessingService` listens via `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)` (CLAUDE.md constraint #6). This ensures:
- The webhook event deduplication record is committed before processing starts.
- Processing failures don't roll back the deduplication record (the event was received).
- Each processing attempt runs in its own transaction.

**Why not `@Async` or `CompletableFuture`?** The established codebase pattern uses Spring's `@TransactionalEventListener` for post-commit work. This is simpler, avoids thread pool configuration, and maintains the existing architectural consistency. The controller wraps the event deduplication insert + event publish inside a `@Transactional` method, so the `AFTER_COMMIT` listener fires only after the dedup record is persisted.

### How ChannelOrderAdapter Abstraction Works

`ChannelOrderAdapter` is an interface with two methods:
- `parse(rawPayload: String): ChannelOrder` — transforms channel-specific JSON into a normalized `ChannelOrder` model.
- `channelName(): String` — returns the channel identifier (e.g., `"shopify"`).

`ShopifyOrderAdapter` implements this for Shopify's `orders/create` webhook payload. Future adapters (Amazon, eBay, TikTok) implement the same interface. The processing service accepts `ChannelOrderAdapter` and operates on the normalized `ChannelOrder` model — no channel-specific logic in the core processing path.

### How SKU Resolution Works Across the Module Boundary

The `platform_listings` table lives in the catalog module. The fulfillment module needs to look up `sku_id` by `external_listing_id` + `platform`. Following the capital module's established pattern (`JpaActiveSkuProvider`, `JpaOrderAmountProvider`), the fulfillment module uses a native query via `EntityManager` to read `platform_listings` directly without importing the catalog module. This preserves bounded-context independence.

Similarly, vendor ID resolution uses a native query against `vendor_sku_assignments` — the same table the vendor module owns. The fulfillment module already depends on the vendor module (`implementation(project(":vendor"))`), but the native query approach is cleaner for this read-only lookup.

---

## Architecture Decisions

### AD-1: Filter-level HMAC verification (not controller-level)

**Decision:** HMAC verification executes in a servlet filter, not in the controller.

**Rationale:** The filter intercepts the request before Spring's `@RequestBody` deserialization. This means:
1. Invalid signatures are rejected before any JSON parsing or application logic runs.
2. The raw request body bytes are available for HMAC computation (not a re-serialized copy).
3. The controller receives only authenticated requests, keeping it focused on business logic.
4. This matches Shopify's own guidance and security best practices for webhook verification.

### AD-2: Spring ApplicationEvent for async processing (not @Async)

**Decision:** Use `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` for async processing.

**Rationale:** This is the established codebase pattern used by `PricingInitializer`, `PlatformListingListener`, `ShutdownRuleListener`, `OrderEventListener`, `CatalogComplianceListener`, and `ComplianceOrchestrator`. All follow the same AFTER_COMMIT + REQUIRES_NEW pattern documented in CLAUDE.md constraint #6 and validated by PM-001 and PM-005 postmortems. Using `@Async` would introduce a different concurrency model (thread pool), require new configuration, and break the established pattern.

**Important nuance:** The controller method that receives the webhook must itself be `@Transactional` so that the deduplication record insert commits before the `AFTER_COMMIT` listener fires. The listener then processes the order in a new transaction.

### AD-3: One internal Order per Shopify line item (not one per Shopify order)

**Decision:** Each resolvable line item in a Shopify order creates a separate internal `Order`.

**Rationale:** The fulfillment module routes orders to individual vendors. A single Shopify order may contain items from different vendors (different SKUs sourced from different suppliers). Each vendor relationship, fulfillment timeline, and shipment tracking is independent. The `idempotencyKey` is constructed as `shopify:order:{shopifyOrderId}:item:{lineItemIndex}` to ensure exactly one internal order per line item per Shopify order.

### AD-4: Cross-module data access via native queries (not module dependency)

**Decision:** Use `EntityManager.createNativeQuery()` to read `platform_listings` and `vendor_sku_assignments` tables, following the capital module's pattern.

**Rationale:**
- The capital module established this pattern with `JpaActiveSkuProvider` (reads `skus` table via native query) and `JpaOrderAmountProvider` (reads `orders` table via native query). Neither module imports the other's code.
- Option A (shared interface in `:shared`) would pull domain-specific concerns into the shared module.
- Option B (fulfillment depends on catalog via `implementation(project(":catalog"))`) creates a new dependency edge that doesn't exist today and violates bounded-context independence.
- Option C (REST API) is too heavy for an internal monolith call.
- Native queries are read-only, simple, and the tables are co-located in the same database.

### AD-5: Shopify order metadata stored on the Order entity via new columns

**Decision:** Add `channel`, `channel_order_id`, and `channel_order_number` columns to the `orders` table.

**Rationale:** BR-8 requires storing Shopify order ID and order number for cross-reference. These are generic enough to support future channels (Amazon order ID, eBay order number, etc.). Adding them to the existing `orders` table (not a separate table) keeps the data model simple and avoids joins for the most common query pattern: "find the internal order for Shopify order #1001."

### AD-6: Webhook event deduplication in a dedicated table (not in-memory)

**Decision:** Create a `webhook_events` table to track processed event IDs with a `processed_at` timestamp.

**Rationale:** NFR-6 requires deduplication records to survive application restarts. An in-memory `ConcurrentHashMap` would lose state on restart, causing duplicate order creation from Shopify retries. The table has a TTL cleanup — events older than 24 hours can be purged by a scheduled job, since Shopify's retry window is only 4 hours.

### AD-7: Customer ID handling

**Decision:** Generate a deterministic customer UUID from the Shopify customer email address using `UUID.nameUUIDFromBytes()`.

**Rationale:** The `Order` entity requires a `customerId: UUID`. There is no customer table in the system today — customer management is not in scope for Phase 1. Using `UUID.nameUUIDFromBytes(email.toByteArray())` produces a deterministic, stable UUID for the same email address, which means repeat customers get the same `customerId` across orders. This enables future customer-level analytics without requiring a customer entity now.

---

## Layer-by-Layer Implementation

### Layer 1: Security — HMAC Verification Filter

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/CachingRequestWrapper.kt`** (NEW)

A `jakarta.servlet.http.HttpServletRequestWrapper` that:
- Reads and caches the entire request body on first `getInputStream()` call.
- Returns a `ByteArrayInputStream` over the cached bytes on subsequent reads.
- Exposes `getCachedBody(): ByteArray` for HMAC computation.

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyHmacVerificationFilter.kt`** (NEW)

A `jakarta.servlet.Filter` implementation:
- Constructor takes `ShopifyWebhookProperties` (injected via `FilterRegistrationBean`).
- On `doFilter()`:
  1. Wraps request in `CachingRequestWrapper`.
  2. Reads `X-Shopify-Hmac-SHA256` header. If absent, returns 401.
  3. For each secret in the configured list:
     - Computes `HmacSHA256(cachedBody, secret)`.
     - Base64-encodes the result.
     - Compares against the header using `MessageDigest.isEqual()`.
  4. If any match: chain the wrapped request through.
  5. If none match: log at WARN (remote address only, never body or HMAC value), return 401.
- No `internal` constructor (CLAUDE.md #14).

### Layer 2: Configuration

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/ShopifyWebhookProperties.kt`** (NEW)

```kotlin
@ConfigurationProperties(prefix = "shopify.webhook")
data class ShopifyWebhookProperties(
    val secrets: List<String> = emptyList(),
    val replayProtection: ReplayProtection = ReplayProtection()
) {
    data class ReplayProtection(
        val enabled: Boolean = false,
        val maxAgeSeconds: Long = 300
    )
}
```

Secrets come from `shopify.webhook.secrets` (YAML list or comma-separated env var `SHOPIFY_WEBHOOK_SECRETS`). Empty defaults per CLAUDE.md constraint #13.

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/ShopifyWebhookFilterConfig.kt`** (NEW)

Registers `ShopifyHmacVerificationFilter` via `FilterRegistrationBean`:
- Maps to URL pattern `/webhooks/shopify/*`.
- Sets filter order to run early (high precedence).
- Injects `ShopifyWebhookProperties`.

### Layer 3: Handler Layer — Webhook Controller

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyWebhookController.kt`** (NEW)

```
@RestController
@RequestMapping("/webhooks/shopify")
class ShopifyWebhookController(
    private val webhookEventRepository: WebhookEventRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: ShopifyWebhookProperties,
    private val objectMapper: ObjectMapper
)
```

**`POST /orders`:**
1. Read `X-Shopify-Topic` header. If not `orders/create`, return 400.
2. Read `X-Shopify-Event-Id` header. If already in `webhook_events` table, log at INFO and return 200.
3. Optional: if replay protection enabled, read `X-Shopify-Triggered-At` header. If older than `maxAgeSeconds`, return 400.
4. Persist `WebhookEvent(eventId, topic, receivedAt)` — this is the deduplication record.
5. Publish `ShopifyOrderReceivedEvent(rawPayload, shopifyEventId)`.
6. Return 200 with body `{"status": "accepted"}`.

Steps 4-5 must be inside a `@Transactional` method so the deduplication record is committed before the `AFTER_COMMIT` listener fires.

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyOrderReceivedEvent.kt`** (NEW)

```kotlin
data class ShopifyOrderReceivedEvent(
    val rawPayload: String,
    val shopifyEventId: String
)
```

A plain data class (Spring `ApplicationEvent` subclassing is optional in modern Spring — any object works with `ApplicationEventPublisher`).

### Layer 4: Domain Layer — Channel Order Abstraction

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrderAdapter.kt`** (NEW)

```kotlin
interface ChannelOrderAdapter {
    fun parse(rawPayload: String): ChannelOrder
    fun channelName(): String
}
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrder.kt`** (NEW)

```kotlin
data class ChannelOrder(
    val channelOrderId: String,        // e.g., Shopify order ID "12345"
    val channelOrderNumber: String,    // e.g., "#1001"
    val channelName: String,           // e.g., "shopify"
    val customerEmail: String,
    val currencyCode: String,          // e.g., "USD"
    val lineItems: List<ChannelLineItem>
)

data class ChannelLineItem(
    val externalProductId: String,     // Shopify product_id
    val externalVariantId: String?,    // Shopify variant_id
    val quantity: Int,
    val unitPrice: BigDecimal,         // price per unit
    val title: String                  // for logging/debugging
)
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapter.kt`** (NEW)

```
@Component
class ShopifyOrderAdapter(
    private val objectMapper: ObjectMapper
) : ChannelOrderAdapter
```

- `parse(rawPayload)`: Deserializes Shopify's `orders/create` JSON.
  - Extracts `id` (order ID), `name` (order number like "#1001"), `currency`, `customer.email`.
  - Iterates `line_items[]`: extracts `product_id`, `variant_id`, `quantity`, `price`, `title`.
  - Uses Jackson `get()` not `path()` for null-coalescing (CLAUDE.md #15).
  - Returns `ChannelOrder`.
- `channelName()`: returns `"shopify"`.
- No `internal` constructor (CLAUDE.md #14).

### Layer 5: Domain Layer — Processing Service

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/ShopifyOrderProcessingService.kt`** (NEW)

```
@Component
class ShopifyOrderProcessingService(
    private val shopifyOrderAdapter: ShopifyOrderAdapter,
    private val platformListingResolver: PlatformListingResolver,
    private val vendorSkuResolver: VendorSkuResolver,
    private val orderService: OrderService
)
```

**`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`:**
`fun onOrderReceived(event: ShopifyOrderReceivedEvent)`:

1. Parse payload: `val channelOrder = shopifyOrderAdapter.parse(event.rawPayload)`.
2. Generate deterministic customer ID: `UUID.nameUUIDFromBytes(channelOrder.customerEmail.toByteArray())`.
3. Resolve currency: `Currency.valueOf(channelOrder.currencyCode)`.
4. For each line item (indexed):
   a. Resolve SKU ID: `platformListingResolver.resolveSkuId(lineItem.externalProductId, lineItem.externalVariantId, "SHOPIFY")`.
   b. If null: log WARN ("Unresolvable line item: productId={}, variantId={}, title={}"), skip.
   c. Resolve vendor ID: `vendorSkuResolver.resolveVendorId(skuId)`.
   d. If null: log WARN ("No vendor assignment for SKU {}"), skip.
   e. Construct `CreateOrderCommand`:
      - `skuId = skuId`
      - `vendorId = vendorId`
      - `customerId = customerUUID`
      - `totalAmount = Money.of(lineItem.unitPrice * lineItem.quantity, currency)`
      - `paymentIntentId = "shopify:order:${channelOrder.channelOrderId}"`
      - `idempotencyKey = "shopify:order:${channelOrder.channelOrderId}:item:${index}"`
   f. Call `orderService.create(command)`.
   g. If `created == true`: update the order's channel metadata (channel, channelOrderId, channelOrderNumber) via a new `OrderService.setChannelMetadata()` method.
   h. Log result at INFO.

5. Log summary: "Processed Shopify order {}: {} of {} line items resolved, {} orders created".

### Layer 6: Proxy Layer — Cross-Module Resolvers

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/PlatformListingResolver.kt`** (NEW)

```
@Component
class PlatformListingResolver(
    @PersistenceContext private val entityManager: EntityManager
)
```

Uses native query following `JpaActiveSkuProvider` / `JpaOrderAmountProvider` pattern:

```kotlin
fun resolveSkuId(externalListingId: String, externalVariantId: String?, platform: String): UUID? {
    val query = if (externalVariantId != null) {
        entityManager.createNativeQuery(
            """SELECT sku_id FROM platform_listings
               WHERE external_listing_id = :listingId
               AND external_variant_id = :variantId
               AND platform = :platform
               AND status = 'ACTIVE'"""
        ).setParameter("listingId", externalListingId)
         .setParameter("variantId", externalVariantId)
         .setParameter("platform", platform)
    } else {
        entityManager.createNativeQuery(
            """SELECT sku_id FROM platform_listings
               WHERE external_listing_id = :listingId
               AND platform = :platform
               AND status = 'ACTIVE'"""
        ).setParameter("listingId", externalListingId)
         .setParameter("platform", platform)
    }
    val results = query.resultList
    return if (results.isNotEmpty()) results.first() as UUID else null
}
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/VendorSkuResolver.kt`** (NEW)

```
@Component
class VendorSkuResolver(
    @PersistenceContext private val entityManager: EntityManager
)
```

```kotlin
fun resolveVendorId(skuId: UUID): UUID? {
    val results = entityManager.createNativeQuery(
        """SELECT vendor_id FROM vendor_sku_assignments
           WHERE sku_id = :skuId AND active = true
           ORDER BY assigned_at DESC LIMIT 1"""
    ).setParameter("skuId", skuId).resultList
    return if (results.isNotEmpty()) results.first() as UUID else null
}
```

### Layer 7: Persistence Layer

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/persistence/WebhookEvent.kt`** (NEW)

```kotlin
@Entity
@Table(name = "webhook_events")
class WebhookEvent(
    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    val eventId: String,

    @Column(name = "topic", nullable = false, length = 100)
    val topic: String,

    @Column(name = "channel", nullable = false, length = 50)
    val channel: String = "shopify",

    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: Instant = Instant.now()
)
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/persistence/WebhookEventRepository.kt`** (NEW)

```kotlin
@Repository
interface WebhookEventRepository : JpaRepository<WebhookEvent, String> {
    fun existsByEventId(eventId: String): Boolean
    fun deleteByProcessedAtBefore(cutoff: Instant): Long
}
```

**File: `modules/app/src/main/resources/db/migration/V20__webhook_events_and_order_channel.sql`** (NEW)

```sql
-- Webhook event deduplication table
CREATE TABLE webhook_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    channel      VARCHAR(50)  NOT NULL DEFAULT 'shopify',
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_events_processed_at ON webhook_events(processed_at);

-- Add channel metadata columns to orders table
ALTER TABLE orders ADD COLUMN channel VARCHAR(50);
ALTER TABLE orders ADD COLUMN channel_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN channel_order_number VARCHAR(100);

CREATE INDEX idx_orders_channel_order_id ON orders(channel_order_id);
```

### Layer 8: Order Entity and Service Modifications

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/Order.kt`** (MODIFIED)

Add three new nullable columns:

```kotlin
@Column(name = "channel")
var channel: String? = null,

@Column(name = "channel_order_id")
var channelOrderId: String? = null,

@Column(name = "channel_order_number")
var channelOrderNumber: String? = null,
```

These are `var` and nullable because:
- Orders created via the existing REST API (manual/test) don't have channel metadata.
- Channel metadata is set after order creation in the processing service.

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`** (MODIFIED)

Add method:

```kotlin
@Transactional
fun setChannelMetadata(orderId: UUID, channel: String, channelOrderId: String, channelOrderNumber: String): Order {
    val order = orderRepository.findById(orderId)
        .orElseThrow { IllegalArgumentException("Order $orderId not found") }
    order.channel = channel
    order.channelOrderId = channelOrderId
    order.channelOrderNumber = channelOrderNumber
    order.updatedAt = Instant.now()
    return orderRepository.save(order)
}
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/dto/OrderResponse.kt`** (MODIFIED)

Add fields:

```kotlin
val channel: String? = null,
val channelOrderId: String? = null,
val channelOrderNumber: String? = null,
```

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/OrderController.kt`** (MODIFIED)

Update `toResponse()` extension to include new fields.

### Layer 9: Webhook Event Cleanup

**File: `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/WebhookEventCleanupJob.kt`** (NEW)

```
@Component
class WebhookEventCleanupJob(
    private val webhookEventRepository: WebhookEventRepository
)
```

`@Scheduled(cron = "0 0 3 * * *")` — runs daily at 3 AM.
Deletes events older than 24 hours via `webhookEventRepository.deleteByProcessedAtBefore(Instant.now().minus(24, ChronoUnit.HOURS))`.
Logs count of purged records at INFO.

Not `@Transactional` at orchestrator level (CLAUDE.md constraint #9). The single `deleteByProcessedAtBefore` call is transactional by Spring Data JPA default.

### Layer 10: Application Configuration

**File: `modules/app/src/main/resources/application.yml`** (MODIFIED)

Add under `shopify:`:

```yaml
shopify:
  webhook:
    secrets: ${SHOPIFY_WEBHOOK_SECRETS:}
    replay-protection:
      enabled: false
      max-age-seconds: 300
```

---

## Task Breakdown

### Layer 1: Security
- [x] Create `CachingRequestWrapper` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/CachingRequestWrapper.kt`
- [x] Create `ShopifyHmacVerificationFilter` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyHmacVerificationFilter.kt`

### Layer 2: Configuration
- [x] Create `ShopifyWebhookProperties` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/ShopifyWebhookProperties.kt`
- [x] Create `ShopifyWebhookFilterConfig` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/ShopifyWebhookFilterConfig.kt`
- [x] Add `shopify.webhook` properties to `modules/app/src/main/resources/application.yml`

### Layer 3: Handler
- [x] Create `ShopifyOrderReceivedEvent` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyOrderReceivedEvent.kt`
- [x] Create `ShopifyWebhookController` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/ShopifyWebhookController.kt`

### Layer 4: Channel Order Abstraction
- [x] Create `ChannelOrderAdapter` interface in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrderAdapter.kt`
- [x] Create `ChannelOrder` and `ChannelLineItem` data classes in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrder.kt`
- [x] Create `ShopifyOrderAdapter` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapter.kt`

### Layer 5: Processing Service
- [x] Create `ShopifyOrderProcessingService` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/ShopifyOrderProcessingService.kt`

### Layer 6: Cross-Module Resolvers
- [x] Create `PlatformListingResolver` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/PlatformListingResolver.kt`
- [x] Create `VendorSkuResolver` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/VendorSkuResolver.kt`

### Layer 7: Persistence
- [x] Create `WebhookEvent` entity in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/persistence/WebhookEvent.kt`
- [x] Create `WebhookEventRepository` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/persistence/WebhookEventRepository.kt`
- [x] Create `V20__webhook_events_and_order_channel.sql` migration in `modules/app/src/main/resources/db/migration/`

### Layer 8: Order Entity Modifications
- [x] Add `channel`, `channelOrderId`, `channelOrderNumber` fields to `Order.kt`
- [x] Add `setChannelMetadata()` method to `OrderService.kt`
- [x] Add channel fields to `OrderResponse.kt`
- [x] Update `OrderController.toResponse()` to include channel fields

### Layer 9: Cleanup Job
- [x] Create `WebhookEventCleanupJob` in `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/WebhookEventCleanupJob.kt`

### Layer 10: Tests
- [x] Unit test: `ShopifyHmacVerificationFilterTest` — valid HMAC passes, invalid HMAC returns 401, rotated secret (old + new both work), missing header returns 401, constant-time comparison verified
- [x] Unit test: `CachingRequestWrapperTest` — body readable multiple times, byte content preserved
- [x] Unit test: `ShopifyOrderAdapterTest` — parses recorded Shopify payload, extracts all fields, handles missing optional fields, uses `get()` not `path()`
- [x] Unit test: `ShopifyOrderProcessingServiceTest` — happy path (2 line items, 2 orders created), partial resolution (1 of 2 resolvable), no resolution (both skip), idempotent reprocessing, vendor not found skips
- [x] Unit test: `PlatformListingResolverTest` — found by listing+variant, found by listing only, not found returns null
- [x] Unit test: `VendorSkuResolverTest` — found returns vendor UUID, not found returns null, inactive assignment ignored
- [x] Unit test: `WebhookEventCleanupJobTest` — deletes old events, preserves recent events
- [x] MockMvc test: `ShopifyWebhookControllerTest` — valid webhook returns 200, duplicate event returns 200 without processing, invalid topic returns 400, replay protection (when enabled) rejects old timestamps
- [x] Unit test: `OrderServiceTest` — add test for `setChannelMetadata()`
- [x] Integration test: `ShopifyWebhookIntegrationTest` — full flow with recorded payload, valid HMAC, verifies orders created in DB
- [x] Integration test: `ShopifyWebhookHmacIntegrationTest` — invalid HMAC returns 401, valid returns 200
- [x] Integration test: `ShopifyWebhookDeduplicationTest` — same event ID sent twice, only one set of orders created

### Layer 11: E2E Playbook and Documentation
- [x] Update `docs/e2e-test-playbook.md` — add Phase 2.0: Shopify Webhook Order Creation (between current Phase 1 and Phase 2)

---

## Testing Strategy

### Unit Tests

All unit tests use `@ExtendWith(MockitoExtension::class)` with `mockito-kotlin`, following the established `OrderServiceTest` / `OrderControllerTest` patterns.

1. **ShopifyHmacVerificationFilterTest:**
   - Valid HMAC with first secret: filter chains request through.
   - Valid HMAC with second secret (rotation): filter chains request through.
   - Invalid HMAC: filter returns 401, does not chain.
   - Missing `X-Shopify-Hmac-SHA256` header: returns 401.
   - Verify `MessageDigest.isEqual()` is used (not String equals).
   - Verify request body is available for re-reading after HMAC check (via `CachingRequestWrapper`).

2. **ShopifyOrderAdapterTest:**
   - Parse a recorded Shopify `orders/create` payload (included as a test resource JSON file).
   - Assert exact values: order ID, order number, customer email, currency, line item count.
   - Assert each line item: product_id, variant_id, quantity, unit price, title.
   - Verify Jackson `get()` is used for field extraction (not `path()`).
   - Handle missing `customer` object gracefully (fallback email).
   - Handle line item with null `variant_id`.

3. **ShopifyOrderProcessingServiceTest:**
   - **Happy path:** 2 line items, both resolve to SKUs and vendors. Verify 2 `CreateOrderCommand` calls with exact values: correct `skuId`, `vendorId`, `totalAmount` (unit price * quantity as `Money`), `paymentIntentId` format, `idempotencyKey` format.
   - **Partial resolution:** 2 line items, one resolves, one doesn't. Verify 1 order created, 1 warning logged.
   - **No resolution:** All line items unresolvable. Verify 0 orders created, warnings logged.
   - **Idempotent reprocessing:** `OrderService.create()` returns `(order, false)`. Verify `setChannelMetadata()` is NOT called again.
   - **Vendor not found:** SKU resolves but vendor doesn't. Verify line item skipped with warning.
   - **Financial value assertions:** Assert exact `Money` amounts — no `any<Money>()` matchers (testing convention).

4. **PlatformListingResolverTest / VendorSkuResolverTest:**
   - These require `EntityManager` mocking. Mock the native query chain: `createNativeQuery() -> setParameter() -> resultList`.
   - Assert correct SQL and parameter binding.
   - Assert null return when no results.

5. **MockMvc Controller Test (ShopifyWebhookControllerTest):**
   - Register `ShopifyWebhookController` in standalone MockMvc setup (same pattern as `OrderControllerTest`).
   - Valid POST with all required headers: returns 200 with `{"status": "accepted"}`.
   - Duplicate `X-Shopify-Event-Id`: returns 200, verify no `ShopifyOrderReceivedEvent` published.
   - Invalid `X-Shopify-Topic` (e.g., `orders/updated`): returns 400.
   - Replay protection enabled + stale timestamp: returns 400.
   - Replay protection disabled + stale timestamp: returns 200 (not rejected).

### Integration Tests

Integration tests go in the `modules/fulfillment/src/test/` directory, following the `OrderLifecycleTest` pattern.

1. **ShopifyWebhookIntegrationTest:**
   - Uses a recorded Shopify `orders/create` JSON payload (test resource file).
   - Computes valid HMAC from the payload + a known test secret.
   - Mocks: `PlatformListingResolver` returns known SKU IDs, `VendorSkuResolver` returns known vendor IDs, `InventoryChecker` returns true.
   - Sends POST to `/webhooks/shopify/orders` with valid headers.
   - Verifies: HTTP 200, `WebhookEvent` persisted, `OrderService.create()` called with correct parameters.

2. **HMAC Rejection Test:**
   - Sends same payload with incorrect HMAC.
   - Verifies: HTTP 401, no `WebhookEvent` persisted, `OrderService.create()` never called.

3. **Deduplication Test:**
   - Sends same payload + event ID twice.
   - Verifies: both return 200, only first creates orders.

### Test Resource File

**File: `modules/fulfillment/src/test/resources/shopify/orders-create-webhook.json`** (NEW)

A recorded Shopify `orders/create` webhook payload based on Shopify's API documentation, containing:
- Order with 2 line items (different product IDs).
- Customer object with email.
- Currency, order name (#1001 format), and payment gateway names.
- This file is referenced by all webhook-related tests for consistency.

### Financial Value Assertions

Per testing conventions: all monetary values assert exact `Money` amounts.

Example:
```kotlin
// Line item: price "29.99", quantity 2, currency "USD"
verify(orderService).create(argThat<CreateOrderCommand> {
    totalAmount == Money.of(BigDecimal("59.98"), Currency.USD) &&
    idempotencyKey == "shopify:order:12345:item:0"
})
```

### Failure-Path Tests

Per testing conventions:
- **Processing failure after dedup:** If `ShopifyOrderProcessingService` throws during processing, the deduplication record (already committed) prevents re-entry. The error is logged. This is tested by making `OrderService.create()` throw and verifying the error is logged at ERROR level.
- **Transient DB failure:** If `WebhookEventRepository.save()` fails, the transaction rolls back, the controller returns 500, and Shopify retries — which is the correct behavior (the event was not recorded, so retry is safe).

---

## Rollout Plan

### Step 1: Persistence Layer (no dependencies)
1. Create `V20__webhook_events_and_order_channel.sql` migration.
2. Create `WebhookEvent` entity and `WebhookEventRepository`.
3. Modify `Order.kt` to add channel metadata fields.
4. Run `./gradlew flywayMigrate` to validate migration.

### Step 2: Configuration (depends on Step 1)
1. Create `ShopifyWebhookProperties`.
2. Add `shopify.webhook` section to `application.yml`.

### Step 3: Security Filter (depends on Step 2)
1. Create `CachingRequestWrapper`.
2. Create `ShopifyHmacVerificationFilter`.
3. Create `ShopifyWebhookFilterConfig`.
4. Write and run filter unit tests.

### Step 4: Channel Order Abstraction (no dependencies)
1. Create `ChannelOrderAdapter` interface.
2. Create `ChannelOrder` / `ChannelLineItem` data classes.
3. Create `ShopifyOrderAdapter`.
4. Create test resource JSON file.
5. Write and run adapter unit tests.

### Step 5: Cross-Module Resolvers (depends on Step 1)
1. Create `PlatformListingResolver` (native query).
2. Create `VendorSkuResolver` (native query).
3. Write and run resolver unit tests.

### Step 6: Order Service Modifications (depends on Step 1)
1. Add `setChannelMetadata()` to `OrderService`.
2. Update `OrderResponse` and `OrderController.toResponse()`.
3. Write and run unit tests.

### Step 7: Processing Service (depends on Steps 4, 5, 6)
1. Create `ShopifyOrderProcessingService`.
2. Write and run processing service unit tests.

### Step 8: Controller (depends on Steps 2, 3, 7)
1. Create `ShopifyWebhookController`.
2. Write and run MockMvc controller tests.

### Step 9: Integration Tests (depends on all above)
1. Write full-flow integration test.
2. Write HMAC rejection test.
3. Write deduplication test.
4. Run `./gradlew build` — all tests pass.

### Step 10: Cleanup and Documentation
1. Create `WebhookEventCleanupJob`.
2. Update `docs/e2e-test-playbook.md` with webhook test phase.

### Risks and Mitigations

| Risk | Mitigation |
|---|---|
| HMAC computation on raw body bytes vs. Spring-deserialized body | `CachingRequestWrapper` preserves raw bytes before any transformation; filter runs before controller |
| Shopify webhook timeout (5 seconds) | HTTP 200 returned immediately; all processing is async via AFTER_COMMIT event listener |
| Shopify unsubscribes webhook after 8 consecutive failures | Deduplication returns 200 for already-seen events; HMAC filter returns 401 fast; processing failures don't affect HTTP response |
| Platform listings table queried from fulfillment module | Native query (read-only) follows established capital module pattern; no new module dependency edge |
| Clock skew causing replay protection false rejections | Replay protection disabled by default (configurable); can be toggled per environment |
| Order entity modified — existing tests may break | New columns are nullable with no default; existing Order construction unchanged; migration uses ALTER TABLE ADD COLUMN (nullable) |
| Shopify customer without email | Fallback to `"unknown-{shopify_customer_id}@noemail.shopify"` for deterministic UUID generation; logged at WARN |
