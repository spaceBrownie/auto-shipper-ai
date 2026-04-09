# FR-026: CJ Tracking Webhook + Shopify Fulfillment Sync -- Implementation Plan

## Technical Design

### Overview

This feature closes the gap between "supplier order placed" (FR-025) and "shipment tracked to delivery" (FR-008). When CJ Dropshipping ships an order, it pushes a tracking webhook to `POST /webhooks/cj/tracking`. The system receives the webhook, deduplicates it, matches the CJ `orderNumber` to an internal order UUID, transitions the order to `SHIPPED` with tracking details, and publishes an `OrderShipped` domain event. A decoupled listener reacts to `OrderShipped` by calling Shopify's `fulfillmentCreateV2` GraphQL mutation to push the tracking number to the customer. The existing `ShipmentTracker` (FR-008) then auto-activates on the next 30-minute polling cycle.

### Data Flow

```
CJ Dropshipping ships order
  -> POST /webhooks/cj/tracking with tracking payload
  -> CjWebhookTokenVerificationFilter validates token (401 if invalid)
  -> CjTrackingWebhookController receives body
  -> Dedup via WebhookEventPersister (channel = "cj", eventId = "cj:{cjOrderId}:{trackNumber}")
  -> Publish CjTrackingReceivedEvent (in-transaction Spring event)
  -> HTTP 200 returned immediately
  -> CjTrackingProcessingService (AFTER_COMMIT + REQUIRES_NEW) handles event
  -> Parse JSON with NullNode guards (CLAUDE.md #15, #17)
  -> OrderRepository.findById(orderNumber as UUID)
  -> Normalize carrier via CjCarrierMapper
  -> OrderService.markShipped(orderId, trackingNumber, normalizedCarrier)
  -> markShipped publishes OrderShipped domain event
  -> ShopifyFulfillmentSyncListener (AFTER_COMMIT + REQUIRES_NEW) handles OrderShipped
  -> ShopifyFulfillmentAdapter calls fulfillmentCreateV2 GraphQL mutation
  -> ShipmentTracker.pollAllShipments() picks up SHIPPED order on next cycle
  -> Carrier confirms delivery -> DELIVERED -> OrderFulfilled -> capital recorded
```

### Key Design Decisions

#### Decision 1: Two-Phase Processing (Controller + AFTER_COMMIT Listener)

**Problem:** CJ expects HTTP 200 immediately. Heavy processing (order lookup, state transition, Shopify API call) should not block the response.

**Decision:** Follow the exact `ShopifyWebhookController` pattern: the controller deduplicates and publishes an in-transaction Spring event (`CjTrackingReceivedEvent`), then returns HTTP 200. A `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` handler (`CjTrackingProcessingService`) does the actual work.

**Rationale:** This is the established pattern in the codebase (FR-023). It ensures the dedup record is committed before processing begins, and separates webhook acknowledgement from business logic. Per CLAUDE.md #6, the AFTER_COMMIT + REQUIRES_NEW combination is mandatory for cross-concern listeners that write to the DB.

#### Decision 2: Token-Based Auth, Not HMAC

**Problem:** CJ's webhook authentication is token-based (a shared secret sent as a header/parameter), not HMAC-based like Shopify.

**Decision:** Create `CjWebhookTokenVerificationFilter` as a servlet filter (not a Spring `@Component`), registered via `FilterRegistrationBean` in a config class. The filter checks a configurable `Authorization` header (Bearer token) against the stored secret. If CJ sends the token differently (e.g., query param), the filter can be adapted.

**Rationale:** Mirrors `ShopifyHmacVerificationFilter` pattern: not a `@Component`, instantiated by a config class, registered to a URL pattern. This keeps webhook auth at the filter level (unauthenticated requests never reach application code). Token comparison uses `MessageDigest.isEqual()` for constant-time comparison to prevent timing attacks.

#### Decision 3: Carrier Mapping as Static Code, Not DB Table

**Problem:** CJ's `logisticName` is free-text ("USPS", "UPS", "FedEx", "4PX", "YunExpress"). The `ShipmentTracker` matches by `carrierName.lowercase()`.

**Decision:** Create a `CjCarrierMapper` object (Kotlin singleton) with a static `Map<String, String>` mapping CJ logistic names to internal carrier names. Unknown carriers are stored as-is and logged at WARN.

**Rationale:** Per spec NFR-11, the carrier set CJ uses is "small and stable." A DB table adds migration and query overhead for a mapping that changes less than once per quarter. If the mapping grows, it can be promoted to a config property or DB table later. The `ShipmentTracker` will skip unknown carriers with a warning log (already handles this case at line 43-46).

#### Decision 4: OrderShipped Event Published from OrderService.markShipped()

**Problem:** The `markShipped()` method currently transitions the order and saves it, but does not publish a domain event. Downstream Shopify sync needs to react to shipment.

**Decision:** Add `OrderShipped` event publication to `OrderService.markShipped()`, matching the pattern of `routeToVendor()` publishing `OrderConfirmed` and `markDelivered()` publishing `OrderFulfilled`.

**Rationale:** The event should be published from the domain service, not from the webhook handler. This ensures any future caller of `markShipped()` (not just CJ webhooks) triggers the Shopify fulfillment sync. The event is published inside the same transaction as the status update, so the AFTER_COMMIT listener fires only after SHIPPED is committed.

#### Decision 5: Shopify Fulfillment Adapter Uses Existing shopifyRestClient Bean

**Problem:** Need to call Shopify's `fulfillmentCreateV2` GraphQL mutation to push tracking to the customer.

**Decision:** Create `ShopifyFulfillmentAdapter` in the fulfillment module's proxy layer. It injects the existing `shopifyRestClient` bean (from `ExternalApiConfig`) and the `shopify.api.access-token`. It POSTs a GraphQL mutation to `/admin/api/2024-01/graphql.json`.

**Rationale:** No new HTTP client or config needed. The Shopify REST client is already configured with the store's base URL. The adapter just needs to construct the GraphQL mutation body and send it. Follows the same pattern as `ShopifyPriceSyncAdapter` in the pricing module (injects `@Qualifier("shopifyRestClient")` + access token).

#### Decision 6: No Flyway Migration Needed

**Problem:** Do any schema changes exist?

**Decision:** No migration needed. `OrderShipped` is a Spring `ApplicationEvent`, not persisted. The `webhook_events` table already supports `channel = "cj"` (the `channel` column was added in V20). All tracking data goes into existing `ShipmentDetails` embedded columns on `orders`. The carrier mapping is in code.

**Rationale:** Every table and column needed already exists. The `webhook_events` table has the `channel` column. The `orders` table has `tracking_number`, `carrier`, and `status` columns.

## Architecture Decisions

### Module Boundaries

| Module | Changes |
|---|---|
| **shared** | New `OrderShipped` domain event (data class implementing `DomainEvent`) |
| **fulfillment** | CJ webhook controller, auth filter, carrier mapper, tracking processing service, Shopify fulfillment adapter, Shopify fulfillment sync listener; modify `OrderService.markShipped()` to publish `OrderShipped` |
| **app** | CJ webhook filter registration bean, CJ webhook config properties, `application.yml` additions |

No changes to `catalog`, `pricing`, `vendor`, `capital`, `compliance`, or `portfolio`.

### Hexagonal Architecture Layers

| Layer | Component | Responsibility |
|---|---|---|
| **Shared / Events** | `OrderShipped` | Domain event carrying orderId, skuId, trackingNumber, carrier |
| **Handler / Webhook** | `CjTrackingWebhookController` | Receive POST, dedup, publish `CjTrackingReceivedEvent`, return 200 |
| **Handler / Webhook** | `CjWebhookTokenVerificationFilter` | Filter-level Bearer token auth |
| **Handler / Webhook** | `CjTrackingReceivedEvent` | Internal Spring event (raw payload + dedupId) |
| **Handler** | `ShopifyFulfillmentSyncListener` | AFTER_COMMIT listener on `OrderShipped`, delegates to adapter |
| **Domain / Service** | `CjTrackingProcessingService` | AFTER_COMMIT listener on `CjTrackingReceivedEvent`, parses payload, matches order, calls `markShipped()` |
| **Domain / Service** | `OrderService.markShipped()` | Modified to publish `OrderShipped` event after transition |
| **Proxy / Carrier** | `CjCarrierMapper` | Static map from CJ logistic names to internal carrier names |
| **Proxy / Platform** | `ShopifyFulfillmentAdapter` | Calls Shopify `fulfillmentCreateV2` GraphQL mutation |
| **Config** | `CjWebhookFilterConfig` | Registers `CjWebhookTokenVerificationFilter` via `FilterRegistrationBean` |
| **Config** | `CjWebhookProperties` | `@ConfigurationProperties` for CJ webhook secret |

## Layer-by-Layer Implementation

### Layer 1: Shared Module -- OrderShipped Domain Event

**File:** `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderShipped.kt`

```kotlin
data class OrderShipped(
    val orderId: OrderId,
    val skuId: SkuId,
    val trackingNumber: String,
    val carrier: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
```

Follows the exact pattern of `OrderConfirmed` and `OrderFulfilled`, but adds `trackingNumber` and `carrier` since downstream consumers (Shopify fulfillment sync) need them.

### Layer 2: Fulfillment Domain -- OrderService Modification

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

Modify `markShipped()` to publish `OrderShipped` after setting shipment details and saving:

```kotlin
fun markShipped(orderId: UUID, trackingNumber: String, carrier: String): Order {
    // ... existing validation and status transition ...
    val saved = orderRepository.save(order)

    eventPublisher.publishEvent(
        OrderShipped(
            orderId = order.orderId(),
            skuId = order.skuId(),
            trackingNumber = trackingNumber,
            carrier = carrier
        )
    )
    logger.info("Order {} marked SHIPPED with tracking {} via {}, OrderShipped event published",
        orderId, trackingNumber, carrier)
    return saved
}
```

### Layer 3: Fulfillment Proxy -- CjCarrierMapper

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/carrier/CjCarrierMapper.kt`

A Kotlin `object` (singleton) with a static mapping. Not a Spring component -- it has no dependencies.

```kotlin
object CjCarrierMapper {
    private val MAPPING: Map<String, String> = mapOf(
        "usps" to "USPS",
        "ups" to "UPS",
        "fedex" to "FedEx",
        "dhl" to "DHL",
        "4px" to "4PX",
        "yanwen" to "Yanwen",
        "yunexpress" to "YunExpress",
        "cainiao" to "Cainiao",
        "ems" to "EMS"
    )

    fun normalize(cjLogisticName: String): String {
        return MAPPING[cjLogisticName.lowercase()] ?: cjLogisticName
    }
}
```

The `ShipmentTracker` uses `carrierName.lowercase()` to resolve providers (line 35). The mapper ensures common CJ names map to known internal names. Unknown carriers pass through as-is -- the tracker gracefully skips them with a warning.

### Layer 4: Fulfillment Proxy -- ShopifyFulfillmentAdapter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/ShopifyFulfillmentAdapter.kt`

```kotlin
@Component
@Profile("!local")
class ShopifyFulfillmentAdapter(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient,
    @Value("\${shopify.api.access-token:}") private val accessToken: String
) {
    @Retry(name = "shopify-fulfillment")
    fun createFulfillment(shopifyOrderGid: String, trackingNumber: String, carrier: String): Boolean {
        if (accessToken.isBlank()) {
            logger.warn("Shopify access token blank -- cannot create fulfillment")
            return false
        }
        // Build and POST GraphQL mutation to /admin/api/2024-01/graphql.json
        // Parse response for errors, return success/failure
    }
}
```

Key details:
- Uses `@Qualifier("shopifyRestClient")` (same as `ShopifyPriceSyncAdapter`)
- GraphQL mutation: `fulfillmentCreateV2(fulfillment: { ... })` with `notifyCustomer: true`
- Shopify order GID format: `gid://shopify/Order/{numericId}` -- the `channelOrderId` stored on `Order` already uses this format
- `@Retry(name = "shopify-fulfillment")` for transient failures
- `@Profile("!local")` to allow stub in local dev

A local stub (`StubShopifyFulfillmentAdapter`) will be created for local profile.

### Layer 5: Fulfillment Handler -- CJ Webhook Controller

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/CjTrackingWebhookController.kt`

```kotlin
@RestController
@RequestMapping("/webhooks/cj")
class CjTrackingWebhookController(
    private val webhookEventRepository: WebhookEventRepository,
    private val webhookEventPersister: WebhookEventPersister,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper
) {
    @PostMapping("/tracking")
    @Transactional
    fun receiveTracking(@RequestBody body: String): ResponseEntity<Map<String, String>> {
        // 1. Parse just enough to build dedup key
        val root = objectMapper.readTree(body)
        val cjOrderId = root.get("data")?.get("cjOrderId")
            ?.let { if (!it.isNull) it.asText() else null }
        val trackNumber = root.get("data")?.get("trackNumber")
            ?.let { if (!it.isNull) it.asText() else null }

        if (cjOrderId == null || trackNumber == null) {
            logger.warn("CJ tracking webhook missing cjOrderId or trackNumber")
            return ResponseEntity.ok(mapOf("status" to "ignored"))
        }

        val dedupKey = "cj:$cjOrderId:$trackNumber"

        // 2. Fast dedup check
        if (webhookEventRepository.existsByEventId(dedupKey)) {
            return ResponseEntity.ok(mapOf("status" to "already_processed"))
        }

        // 3. Persist dedup record (REQUIRES_NEW isolation)
        val persisted = webhookEventPersister.tryPersist(
            WebhookEvent(eventId = dedupKey, topic = "tracking/update", channel = "cj")
        )
        if (!persisted) {
            return ResponseEntity.ok(mapOf("status" to "already_processed"))
        }

        // 4. Publish internal event for AFTER_COMMIT processing
        eventPublisher.publishEvent(
            CjTrackingReceivedEvent(rawPayload = body, dedupKey = dedupKey)
        )

        return ResponseEntity.ok(mapOf("status" to "accepted"))
    }
}
```

Note on CJ webhook payload structure: CJ wraps the tracking data inside a `data` object. The controller extracts just enough fields for dedup. Full parsing happens in the processing service.

### Layer 6: Fulfillment Handler -- CjTrackingReceivedEvent

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/CjTrackingReceivedEvent.kt`

```kotlin
data class CjTrackingReceivedEvent(
    val rawPayload: String,
    val dedupKey: String
)
```

Follows the `ShopifyOrderReceivedEvent` pattern. A simple in-module Spring event (not a `DomainEvent`), used only to bridge the controller transaction to the AFTER_COMMIT processing service.

### Layer 7: Fulfillment Domain -- CjTrackingProcessingService

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/CjTrackingProcessingService.kt`

```kotlin
@Component
class CjTrackingProcessingService(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    private val objectMapper: ObjectMapper
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrackingReceived(event: CjTrackingReceivedEvent) {
        // 1. Parse full payload with NullNode guards (CLAUDE.md #15, #17)
        val root = objectMapper.readTree(event.rawPayload)
        val data = root.get("data") ?: run {
            logger.warn("CJ tracking webhook missing data node: {}", event.dedupKey)
            return
        }
        val orderNumber = data.get("orderNumber")
            ?.let { if (!it.isNull) it.asText() else null }
        val trackNumber = data.get("trackNumber")
            ?.let { if (!it.isNull) it.asText() else null }
        val logisticName = data.get("logisticName")
            ?.let { if (!it.isNull) it.asText() else null }

        // 2. Validate required fields
        if (orderNumber == null || trackNumber == null) { ... return }

        // 3. Parse UUID
        val orderId = try { UUID.fromString(orderNumber) } catch (e: Exception) { ... return }

        // 4. Lookup order
        val order = orderRepository.findById(orderId).orElse(null)
        if (order == null) { logger.warn(...); return }

        // 5. Guard: only transition from CONFIRMED
        if (order.status != OrderStatus.CONFIRMED) { logger.warn(...); return }

        // 6. Normalize carrier
        val carrier = CjCarrierMapper.normalize(logisticName ?: "unknown")
        if (logisticName != null && carrier == logisticName) {
            logger.warn("Unknown CJ carrier name '{}' for order {}", logisticName, orderId)
        }

        // 7. Mark shipped (publishes OrderShipped event)
        orderService.markShipped(orderId, trackNumber, carrier)
        logger.info("CJ tracking processed: order={}, tracking={}, carrier={}", orderId, trackNumber, carrier)
    }
}
```

### Layer 8: Fulfillment Handler -- ShopifyFulfillmentSyncListener

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/ShopifyFulfillmentSyncListener.kt`

```kotlin
@Component
class ShopifyFulfillmentSyncListener(
    private val shopifyFulfillmentAdapter: ShopifyFulfillmentAdapter,
    private val orderRepository: OrderRepository
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderShipped(event: OrderShipped) {
        val order = orderRepository.findById(event.orderId.value).orElse(null)
        if (order == null) { logger.warn(...); return }

        val channelOrderId = order.channelOrderId
        if (channelOrderId == null) {
            logger.warn("Order {} has no channelOrderId -- skipping Shopify fulfillment sync", order.id)
            return
        }

        try {
            shopifyFulfillmentAdapter.createFulfillment(channelOrderId, event.trackingNumber, event.carrier)
            logger.info("Shopify fulfillment created for order {}", order.id)
        } catch (e: Exception) {
            logger.warn("Shopify fulfillment sync failed for order {}: {}", order.id, e.message)
            // Do NOT rethrow -- order is already SHIPPED, Shopify failure is non-fatal
        }
    }
}
```

Critical: catch all exceptions. A Shopify API failure must never roll back the SHIPPED status. The order is already committed as SHIPPED by the time this listener fires (AFTER_COMMIT guarantees this).

### Layer 9: Fulfillment Handler -- CjWebhookTokenVerificationFilter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/webhook/CjWebhookTokenVerificationFilter.kt`

```kotlin
class CjWebhookTokenVerificationFilter(
    private val expectedToken: String
) : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        if (expectedToken.isBlank()) {
            writeErrorResponse(httpResponse, 401, "CJ webhook secret not configured")
            return
        }

        val wrapper = CachingRequestWrapper(httpRequest)
        val authHeader = httpRequest.getHeader("Authorization")
        val token = authHeader?.removePrefix("Bearer ")?.trim()

        if (token == null || !MessageDigest.isEqual(
                token.toByteArray(), expectedToken.toByteArray())) {
            writeErrorResponse(httpResponse, 401, "Invalid webhook token")
            return
        }

        chain.doFilter(wrapper, httpResponse)
    }
}
```

Not a `@Component` -- instantiated by `CjWebhookFilterConfig`. Reuses `CachingRequestWrapper` so the body can be read later by the controller. Uses `MessageDigest.isEqual()` for constant-time comparison. When `expectedToken` is blank, rejects all requests (per CLAUDE.md #13 pattern).

### Layer 10: Config -- CjWebhookFilterConfig and CjWebhookProperties

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/CjWebhookProperties.kt`

```kotlin
@ConfigurationProperties(prefix = "cj-dropshipping.webhook")
data class CjWebhookProperties(
    val secret: String = ""
)
```

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/config/CjWebhookFilterConfig.kt`

```kotlin
@Configuration
@EnableConfigurationProperties(CjWebhookProperties::class)
class CjWebhookFilterConfig {
    @Bean
    fun cjWebhookTokenFilter(properties: CjWebhookProperties): FilterRegistrationBean<CjWebhookTokenVerificationFilter> {
        val registration = FilterRegistrationBean<CjWebhookTokenVerificationFilter>()
        registration.filter = CjWebhookTokenVerificationFilter(properties.secret)
        registration.addUrlPatterns("/webhooks/cj/*")
        registration.order = 1
        return registration
    }
}
```

### Layer 11: App Config -- application.yml

Add to `application.yml`:

```yaml
cj-dropshipping:
  webhook:
    secret: ${CJ_WEBHOOK_SECRET:}

resilience4j:
  retry:
    instances:
      shopify-fulfillment:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
  circuitbreaker:
    instances:
      shopify-fulfillment:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

## Task Breakdown

### Shared Module

- [x] **S-1:** Create `OrderShipped` domain event in `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderShipped.kt`

### Fulfillment Module -- Domain Layer

- [x] **D-1:** Modify `OrderService.markShipped()` to publish `OrderShipped` event after saving the order
- [x] **D-2:** Create `CjTrackingProcessingService` -- AFTER_COMMIT listener that parses CJ payload, matches order, calls `markShipped()`
- [x] **D-3:** Update `OrderServiceTest` to verify `OrderShipped` event is published on `markShipped()`
- [x] **D-4:** Create `CjTrackingProcessingServiceTest` -- unit test with mocked `OrderRepository` and `OrderService`

### Fulfillment Module -- Proxy Layer

- [x] **P-1:** Create `CjCarrierMapper` object with static carrier name mapping
- [x] **P-2:** Create `ShopifyFulfillmentAdapter` -- calls Shopify `fulfillmentCreateV2` GraphQL mutation
- [x] **P-3:** Create `StubShopifyFulfillmentAdapter` for local profile
- [x] **P-4:** Create `CjCarrierMapperTest` -- unit test for known and unknown carrier names
- [x] **P-5:** Create `ShopifyFulfillmentAdapterWireMockTest` -- WireMock test verifying GraphQL mutation body

### Fulfillment Module -- Handler Layer

- [x] **H-1:** Create `CjTrackingReceivedEvent` data class
- [x] **H-2:** Create `CjTrackingWebhookController` -- POST endpoint, dedup, publish internal event
- [x] **H-3:** Create `CjWebhookTokenVerificationFilter` -- servlet filter with constant-time token comparison
- [x] **H-4:** Create `ShopifyFulfillmentSyncListener` -- AFTER_COMMIT listener on `OrderShipped`, delegates to adapter
- [x] **H-5:** Create `CjTrackingWebhookControllerTest` -- MockMvc test: valid webhook, dedup, missing fields
- [x] **H-6:** Create `CjWebhookTokenVerificationFilterTest` -- unit test: valid token, invalid token, blank secret
- [x] **H-7:** Create `ShopifyFulfillmentSyncListenerTest` -- unit test: happy path, missing channelOrderId, Shopify failure

### Fulfillment Module -- Config Layer

- [x] **C-1:** Create `CjWebhookProperties` configuration properties class
- [x] **C-2:** Create `CjWebhookFilterConfig` -- registers filter via `FilterRegistrationBean`

### App Module -- Configuration

- [x] **A-1:** Add `cj-dropshipping.webhook.secret` to `application.yml`
- [x] **A-2:** Add `shopify-fulfillment` resilience4j retry and circuit breaker config to `application.yml`

### WireMock Fixtures

- [x] **W-1:** Create `wiremock/cj/tracking-webhook-valid.json` -- realistic CJ tracking notification payload (based on CJ API docs)
- [x] **W-2:** Create `wiremock/shopify/fulfillment-create-success.json` -- Shopify `fulfillmentCreateV2` response fixture (based on Shopify Admin GraphQL API docs)
- [x] **W-3:** Create `wiremock/shopify/fulfillment-create-error.json` -- Shopify error response fixture

### Integration Tests

- [x] **I-1:** Create `CjTrackingWebhookIntegrationTest` -- end-to-end: CJ webhook -> order matched -> SHIPPED -> OrderShipped event -> Shopify fulfillment called (WireMock)

## Testing Strategy

### Unit Tests

| Test | Verifies |
|---|---|
| `CjCarrierMapperTest` | Known carriers normalize correctly; unknown carriers pass through as-is |
| `CjTrackingWebhookControllerTest` | MockMvc: valid webhook returns 200 accepted; duplicate returns 200 already_processed; missing fields returns 200 ignored; dedup key format is `cj:{cjOrderId}:{trackNumber}` |
| `CjWebhookTokenVerificationFilterTest` | Valid token passes through; invalid token returns 401; blank secret rejects all; uses CachingRequestWrapper |
| `CjTrackingProcessingServiceTest` | Parses CJ payload, calls `markShipped()` with correct args; order not found logs warning and returns; order not CONFIRMED logs warning and returns; missing trackNumber returns early; carrier normalization applied |
| `ShopifyFulfillmentAdapterWireMockTest` | GraphQL mutation sent with correct tracking number, carrier, `notifyCustomer: true`; error response handled gracefully |
| `ShopifyFulfillmentSyncListenerTest` | On `OrderShipped`, calls adapter with channelOrderId, trackingNumber, carrier; missing channelOrderId skips with warning; adapter exception does not propagate |
| `OrderServiceTest` (updated) | `markShipped()` publishes `OrderShipped` event with correct orderId, skuId, trackingNumber, carrier |

### WireMock Tests

| Test | Verifies |
|---|---|
| `ShopifyFulfillmentAdapterWireMockTest` | HTTP POST to `/admin/api/2024-01/graphql.json` with `X-Shopify-Access-Token` header; request body contains `fulfillmentCreateV2` mutation with tracking number, carrier company, and `notifyCustomer: true`; success response parsed correctly; error response handled gracefully |

### Integration Test

| Test | Verifies |
|---|---|
| `CjTrackingWebhookIntegrationTest` | Full chain: POST to `/webhooks/cj/tracking` with valid payload -> order status transitions to SHIPPED -> tracking number and carrier recorded on ShipmentDetails -> `OrderShipped` event published -> Shopify fulfillment adapter called with correct args (WireMock) |

### Edge Case Coverage (per SC-11)

- Webhook with unknown `orderNumber` (not a valid UUID): logged, HTTP 200 returned
- Webhook with valid UUID but order not found: logged, HTTP 200 returned
- Webhook for order not in CONFIRMED status: logged, HTTP 200 returned
- Webhook with missing `trackNumber`: logged, HTTP 200 returned, no transition
- Shopify API failure: logged, order remains SHIPPED (Shopify exception caught)
- Order without `channelOrderId`: Shopify sync skipped with warning log
- Duplicate webhook (same `cjOrderId` + `trackNumber`): HTTP 200 without re-processing

### CLAUDE.md Constraint Verification

| Constraint | Where Enforced |
|---|---|
| #6 AFTER_COMMIT + REQUIRES_NEW | `CjTrackingProcessingService`, `ShopifyFulfillmentSyncListener` |
| #13 @Value empty defaults | `CjWebhookProperties` (default `""`), `ShopifyFulfillmentAdapter` |
| #14 No internal constructors | All `@Component`/`@Service` classes use public constructors |
| #15 get() not path() | `CjTrackingWebhookController`, `CjTrackingProcessingService` |
| #17 NullNode guard | Every `get()?.let { if (!it.isNull) it.asText() else null }` in JSON parsing |

## Dependency Order

Tasks can be parallelized within tiers. Each tier depends on the previous tier completing.

**Tier 0 (no dependencies):**
- S-1: `OrderShipped` event
- P-1: `CjCarrierMapper`
- H-1: `CjTrackingReceivedEvent`
- C-1: `CjWebhookProperties`

**Tier 1 (depends on Tier 0):**
- D-1: `OrderService.markShipped()` modification (depends on S-1)
- H-3: `CjWebhookTokenVerificationFilter`
- C-2: `CjWebhookFilterConfig` (depends on C-1, H-3)
- P-4: `CjCarrierMapperTest` (depends on P-1)

**Tier 2 (depends on Tier 1):**
- D-3: `OrderServiceTest` update (depends on D-1)
- H-2: `CjTrackingWebhookController` (depends on H-1)
- P-2: `ShopifyFulfillmentAdapter`
- P-3: `StubShopifyFulfillmentAdapter`
- A-1: `application.yml` CJ webhook secret (depends on C-1)
- A-2: `application.yml` resilience4j config

**Tier 3 (depends on Tier 2):**
- D-2: `CjTrackingProcessingService` (depends on D-1, P-1, H-1)
- H-4: `ShopifyFulfillmentSyncListener` (depends on S-1, P-2)
- H-5: `CjTrackingWebhookControllerTest` (depends on H-2)
- H-6: `CjWebhookTokenVerificationFilterTest` (depends on H-3)
- P-5: `ShopifyFulfillmentAdapterWireMockTest` (depends on P-2)
- W-1, W-2, W-3: WireMock fixtures

**Tier 4 (depends on Tier 3):**
- D-4: `CjTrackingProcessingServiceTest` (depends on D-2)
- H-7: `ShopifyFulfillmentSyncListenerTest` (depends on H-4)
- I-1: `CjTrackingWebhookIntegrationTest` (depends on all production code)

## Rollout Plan

### Pre-Deployment

1. Register the CJ webhook URL (`https://{domain}/webhooks/cj/tracking`) with CJ Dropshipping via their `POST /webhook/set` API endpoint.
2. Set `CJ_WEBHOOK_SECRET` environment variable with the token shared with CJ.
3. Verify `SHOPIFY_ACCESS_TOKEN` is already set and has `write_fulfillments` scope.

### Deployment

1. Deploy the new code. No migration needed -- all schema already exists.
2. The CJ webhook filter and controller activate automatically.
3. `ShipmentTracker` continues its 30-minute polling cycle -- no restart needed.

### Verification

1. Place a test order through Shopify -> confirm it routes to CJ.
2. Wait for CJ to ship and push the tracking webhook.
3. Verify in logs: "CJ tracking processed: order=..., tracking=..., carrier=..."
4. Verify in logs: "Shopify fulfillment created for order ..."
5. Verify the customer receives a shipping confirmation email from Shopify.
6. Wait for the `ShipmentTracker` polling cycle and verify tracking status updates.

### Rollback

1. If the CJ webhook causes issues, set `CJ_WEBHOOK_SECRET` to empty string -- all webhooks will be rejected at the filter level (no code changes needed).
2. If Shopify fulfillment sync causes issues, the listener catches all exceptions. Orders will still transition to SHIPPED and the ShipmentTracker will still poll -- only the Shopify customer notification will be missing.
3. Full code rollback: revert the deployment. No migration rollback needed (no new migrations).
