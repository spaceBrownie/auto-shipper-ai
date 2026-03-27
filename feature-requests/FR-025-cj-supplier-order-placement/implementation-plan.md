# FR-025: CJ Supplier Order Placement — Implementation Plan

## Technical Design

### Architecture Overview

FR-025 bridges the gap between order confirmation and actual supplier fulfillment. When `OrderService.routeToVendor()` transitions an order from PENDING to CONFIRMED, it publishes an `OrderConfirmed` domain event. `SupplierOrderPlacementListener` fires `AFTER_COMMIT` in a `REQUIRES_NEW` transaction, looks up the CJ product variant ID via `SupplierProductMapping`, and calls `CjOrderAdapter.placeOrder()` to create a purchase order on CJ Dropshipping. On success, the CJ order ID is stored on the Order entity. On failure (after Resilience4j retries are exhausted), the order transitions to FAILED with a recorded failure reason.

The shipping address flows from the Shopify webhook payload through a new `ShippingAddress` embeddable on the Order entity, and is mapped to CJ's API format by the adapter.

### Data Flow Diagram

```
Shopify Webhook (orders/create)
       |
       v
ShopifyOrderAdapter.parse()  ── NEW: extracts shipping_address fields
       |
       v
ChannelOrder + ChannelShippingAddress (new data class)
       |
       v
LineItemOrderCreator.processLineItem()
       |  builds CreateOrderCommand with quantity + shippingAddress
       v
OrderService.create()  ── persists Order with embedded ShippingAddress + quantity
       |
       v
OrderService.routeToVendor()  ── PENDING -> CONFIRMED
       |  publishes OrderConfirmed event
       |  (transaction commits)
       v
SupplierOrderPlacementListener  (@TransactionalEventListener AFTER_COMMIT + @Transactional REQUIRES_NEW)
       |
       |  1. Check idempotency: if order.supplierOrderId is non-null, skip
       |  2. Look up SupplierProductMapping by skuId -> get CJ vid
       |  3. Call SupplierOrderAdapter.placeOrder(order)
       v
CjOrderAdapter.placeOrder()  (@Retry + @CircuitBreaker)
       |  Maps Order + ShippingAddress -> CJ createOrderV2 request body
       |  Lets RestClientException propagate (CLAUDE.md #18)
       v
CJ Dropshipping API: POST /api2.0/v1/shopping/order/createOrderV2
       |
       v
Success: order.supplierOrderId = CJ order ID, save
Failure: order.updateStatus(FAILED), order.failureReason = error message, save
```

### Module Boundaries

| Change | Module | Layer |
|---|---|---|
| `OrderConfirmed` domain event | `shared` | Domain events |
| `ShippingAddress` embeddable, `Order` entity changes, `OrderStatus.FAILED` | `fulfillment` | Domain |
| `ChannelShippingAddress` data class, `ChannelOrder` changes | `fulfillment` | Domain (channel) |
| `CreateOrderCommand` quantity + shipping address fields | `fulfillment` | Domain (service) |
| `ShopifyOrderAdapter` shipping extraction | `fulfillment` | Domain (channel) |
| `LineItemOrderCreator` quantity + shipping threading | `fulfillment` | Domain (service) |
| `OrderService.routeToVendor()` event publishing | `fulfillment` | Domain (service) |
| `SupplierOrderPlacementListener` | `fulfillment` | Handler (event listener) |
| `SupplierOrderAdapter` interface, `CjOrderAdapter` | `fulfillment` | Proxy |
| `SupplierProductMapping` entity + repository | `fulfillment` | Persistence |
| `SupplierProductMappingResolver` (native SQL) | `fulfillment` | Proxy (platform) |
| Flyway migration V21 | `app` | Database |
| application.yml config additions | `app` | Config |

---

## Architecture Decisions

### AD-1: Event-driven trigger via OrderConfirmed (not direct call from routeToVendor)

**Decision:** `routeToVendor()` publishes an `OrderConfirmed` domain event. The supplier order placement logic runs in a separate `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` listener.

**Rationale:** This follows the established codebase pattern (VendorSlaBreachRefunder, ShopifyOrderProcessingService, PricingInitializer, etc.) documented in CLAUDE.md constraint #6. The CONFIRMED status is committed before supplier placement starts, so a CJ API failure cannot roll back the confirmation. The listener's REQUIRES_NEW transaction isolates supplier-side writes.

**Alternatives considered:**
- Direct call from `routeToVendor()` — would couple order confirmation to supplier API availability. A CJ timeout would block or roll back the confirmation.
- `@Async` — would introduce a different concurrency model than the established `@TransactionalEventListener` pattern.

### AD-2: Resilience4j on the adapter, exception handling in the listener

**Decision:** `CjOrderAdapter.placeOrder()` carries `@Retry` and `@CircuitBreaker` annotations and does NOT catch `RestClientException` internally. The listener catches exceptions after retries are exhausted.

**Rationale:** CLAUDE.md constraint #18 is explicit: methods with `@Retry`/`@CircuitBreaker` must not catch the exception types they should retry. Resilience4j AOP operates at the method boundary. If the adapter catches `RestClientException` internally, retries never fire. PR #37 had exactly this bug.

**Pattern:**
```kotlin
// CjOrderAdapter — lets RestClientException propagate
@Retry(name = "cj-order")
@CircuitBreaker(name = "cj-order")
override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
    // REST call here — no try-catch for RestClientException
    // Only catch non-retryable exceptions (e.g., JSON parsing from a successful response)
}

// SupplierOrderPlacementListener — catches after retries exhausted
try {
    val result = supplierOrderAdapter.placeOrder(request)
    order.supplierOrderId = result.supplierOrderId
    orderRepository.save(order)
} catch (e: Exception) {
    order.updateStatus(OrderStatus.FAILED)
    order.failureReason = e.message ?: "Unknown error"
    orderRepository.save(order)
}
```

### AD-3: SupplierProductMapping in fulfillment via native SQL (not a new module)

**Decision:** The `supplier_product_mappings` table is created via Flyway migration. The fulfillment module reads it via `EntityManager` native SQL (same pattern as `PlatformListingResolver` and `VendorSkuResolver`), avoiding a JPA entity dependency.

**Rationale:** The mapping table bridges demand scan data (portfolio module) and order fulfillment (fulfillment module). Making it a standalone table with native SQL reads from fulfillment preserves bounded-context independence. The portfolio module's demand scan pipeline can populate it in a future enhancement. For Phase 1, seed data is sufficient.

**Alternative considered:** JPA entity in fulfillment module — heavier than needed for a simple lookup table. Native SQL keeps the read-only cross-module pattern consistent.

### AD-4: ShippingAddress as @Embeddable (not a separate entity)

**Decision:** `ShippingAddress` is an `@Embeddable` class embedded on the `Order` entity, adding 8 columns to the `orders` table.

**Rationale:** A shipping address has no independent lifecycle — it is created with the order and never updated independently. An embeddable avoids a separate table, a join, and a separate entity lifecycle. This matches the existing `ShipmentDetails` embeddable pattern on `Order`.

### AD-5: CreateOrderCommand.quantity has no default value

**Decision:** The `quantity` parameter on `CreateOrderCommand` is a required `Int` with no default value.

**Rationale:** PR #37's critical bug was hardcoded `quantity=1`. By making quantity a required constructor parameter, any caller that omits it gets a compile error. This is structural enforcement, not convention-based enforcement. The compiler catches the bug; unit tests cannot.

### AD-6: OrderConfirmed in shared module (not fulfillment)

**Decision:** `OrderConfirmed` is a `DomainEvent` in the `shared` module, alongside `OrderFulfilled`, `VendorSlaBreached`, etc.

**Rationale:** Cross-module events must be in the shared module so any listener in any module can depend on them without introducing module-to-module coupling. Even though the only current listener is in fulfillment, the event semantics are cross-cutting (capital module may want to react to confirmations for reserve calculations).

---

## Layer-by-Layer Implementation

### Layer 1: Database Migration (V21)

**File:** `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql`

```sql
-- 1. Add quantity column to orders (required, default 1 for existing rows only)
ALTER TABLE orders ADD COLUMN quantity INT NOT NULL DEFAULT 1;
-- Remove the default after backfilling existing rows
ALTER TABLE orders ALTER COLUMN quantity DROP DEFAULT;

-- 2. Add shipping address columns to orders (embedded, nullable for existing orders)
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_address2 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip VARCHAR(50);
ALTER TABLE orders ADD COLUMN shipping_country VARCHAR(100);
ALTER TABLE orders ADD COLUMN shipping_country_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_phone VARCHAR(50);

-- 3. Add supplier cross-reference columns
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason VARCHAR(1000);

-- 4. Supplier product mapping table
CREATE TABLE supplier_product_mappings (
    id UUID PRIMARY KEY,
    sku_id UUID NOT NULL REFERENCES skus(id),
    supplier VARCHAR(50) NOT NULL,
    supplier_variant_id VARCHAR(255) NOT NULL,
    supplier_product_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mapping_sku_supplier
    ON supplier_product_mappings(sku_id, supplier);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);
```

**Design notes:**
- `quantity` gets a temporary DEFAULT 1 for existing rows (all pre-FR-025 orders were single-unit), then the default is dropped so new inserts must provide a value.
- Shipping address columns are nullable because existing orders pre-date shipping address capture.
- `supplier_product_mappings` has a unique constraint on `(sku_id, supplier)` — one mapping per SKU per supplier.
- `shipping_address2` is included because Shopify provides `address2` and CJ may need it appended.

### Layer 2: Shared Module — OrderConfirmed Event

**File:** `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt`

```kotlin
data class OrderConfirmed(
    val orderId: OrderId,
    val skuId: SkuId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
```

Follows the exact pattern of `OrderFulfilled`. Carries `orderId` and `skuId` for the listener to load the full Order entity.

### Layer 3: Fulfillment Domain Changes

#### 3a. OrderStatus — add FAILED

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/OrderStatus.kt`

Add `FAILED` to the enum. This is a terminal state (no transitions out of FAILED).

#### 3b. Order entity — new fields + VALID_TRANSITIONS update

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/Order.kt`

New fields:
- `quantity: Int` (required, no default)
- `@Embedded shippingAddress: ShippingAddress` (new embeddable)
- `supplierOrderId: String?` (nullable)
- `failureReason: String?` (nullable)

VALID_TRANSITIONS update:
- Add `OrderStatus.CONFIRMED to setOf(OrderStatus.SHIPPED, OrderStatus.REFUNDED, OrderStatus.FAILED)` — FAILED is a valid transition from CONFIRMED.
- Add `OrderStatus.FAILED to emptySet()` — FAILED is terminal.

#### 3c. ShippingAddress embeddable

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/ShippingAddress.kt`

```kotlin
@Embeddable
class ShippingAddress(
    @Column(name = "shipping_customer_name")
    val customerName: String? = null,

    @Column(name = "shipping_address")
    val address: String? = null,

    @Column(name = "shipping_address2")
    val address2: String? = null,

    @Column(name = "shipping_city")
    val city: String? = null,

    @Column(name = "shipping_province")
    val province: String? = null,

    @Column(name = "shipping_province_code")
    val provinceCode: String? = null,

    @Column(name = "shipping_zip")
    val zip: String? = null,

    @Column(name = "shipping_country")
    val country: String? = null,

    @Column(name = "shipping_country_code")
    val countryCode: String? = null,

    @Column(name = "shipping_phone")
    val phone: String? = null
)
```

All fields nullable with defaults so existing orders (pre-FR-025) can be loaded without data.

#### 3d. ChannelShippingAddress and ChannelOrder update

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrder.kt`

Add `ChannelShippingAddress` data class:
```kotlin
data class ChannelShippingAddress(
    val customerName: String?,
    val address1: String?,
    val address2: String?,
    val city: String?,
    val province: String?,
    val provinceCode: String?,
    val zip: String?,
    val country: String?,
    val countryCode: String?,
    val phone: String?
)
```

Add `shippingAddress: ChannelShippingAddress?` field to `ChannelOrder`.

#### 3e. CreateOrderCommand — add quantity + shippingAddress

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/CreateOrderCommand.kt`

Add:
- `quantity: Int` (required, NO default — compiler enforces PR #37 fix)
- `shippingAddress: ShippingAddress?` (nullable, not all channels provide shipping)

The `quantity` field must be listed before any field with a default so Kotlin's positional arguments catch any omission at compile time.

### Layer 4: Fulfillment Domain Service Changes

#### 4a. ShopifyOrderAdapter — extract shipping_address

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapter.kt`

Add shipping address extraction using Jackson `get()` (not `path()`, per CLAUDE.md #15):
```kotlin
val shippingNode = root.get("shipping_address")
val shippingAddress = if (shippingNode != null && !shippingNode.isNull) {
    ChannelShippingAddress(
        customerName = listOfNotNull(
            shippingNode.get("first_name")?.asText(),
            shippingNode.get("last_name")?.asText()
        ).joinToString(" ").ifBlank { null },
        address1 = shippingNode.get("address1")?.asText(),
        address2 = shippingNode.get("address2")?.asText(),
        city = shippingNode.get("city")?.asText(),
        province = shippingNode.get("province")?.asText(),
        provinceCode = shippingNode.get("province_code")?.asText(),
        zip = shippingNode.get("zip")?.asText(),
        country = shippingNode.get("country")?.asText(),
        countryCode = shippingNode.get("country_code")?.asText(),
        phone = shippingNode.get("phone")?.asText()
    )
} else null
```

Pass `shippingAddress` through to `ChannelOrder`.

#### 4b. LineItemOrderCreator — thread quantity + shippingAddress

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/LineItemOrderCreator.kt`

Update `CreateOrderCommand` construction to include:
- `quantity = lineItem.quantity` (flows from ChannelLineItem, which already has quantity)
- `shippingAddress = channelOrder.shippingAddress?.toShippingAddress()` (convert ChannelShippingAddress -> ShippingAddress)

Add a mapping extension function `ChannelShippingAddress.toShippingAddress(): ShippingAddress`.

#### 4c. OrderService.create() — persist quantity + shippingAddress

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

Update the `Order()` constructor call in `create()` to include `quantity` and `shippingAddress` from the command.

#### 4d. OrderService.routeToVendor() — publish OrderConfirmed

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

After saving the CONFIRMED order, publish `OrderConfirmed`:
```kotlin
eventPublisher.publishEvent(
    OrderConfirmed(
        orderId = order.orderId(),
        skuId = order.skuId()
    )
)
```

This matches the `markDelivered()` pattern which publishes `OrderFulfilled` after save.

#### 4e. OrderService — add markFailed() and setSupplierOrderId()

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

New methods:
```kotlin
@Transactional
fun markFailed(orderId: UUID, reason: String): Order {
    val order = orderRepository.findById(orderId)
        .orElseThrow { IllegalArgumentException("Order $orderId not found") }
    order.updateStatus(OrderStatus.FAILED)
    order.failureReason = reason
    return orderRepository.save(order)
}

@Transactional
fun setSupplierOrderId(orderId: UUID, supplierOrderId: String): Order {
    val order = orderRepository.findById(orderId)
        .orElseThrow { IllegalArgumentException("Order $orderId not found") }
    order.supplierOrderId = supplierOrderId
    order.updatedAt = Instant.now()
    return orderRepository.save(order)
}
```

### Layer 5: Fulfillment Proxy Layer — Supplier Order Adapter

#### 5a. SupplierOrderAdapter interface

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierOrderAdapter.kt`

```kotlin
interface SupplierOrderAdapter {
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderNumber: String,
    val shippingAddress: ShippingAddress,
    val products: List<SupplierOrderProduct>,
    val logisticName: String,
    val fromCountryCode: String
)

data class SupplierOrderProduct(
    val vid: String,
    val quantity: Int
)

data class SupplierOrderResult(
    val supplierOrderId: String,
    val status: String
)
```

#### 5b. CjOrderAdapter (real implementation)

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjOrderAdapter.kt`

```kotlin
@Component
@Profile("!local")
class CjOrderAdapter(
    @Value("${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("${cj-dropshipping.api.access-token:}") private val accessToken: String,
    @Value("${cj-dropshipping.order.logistic-name:}") private val logisticName: String,
    @Value("${cj-dropshipping.order.from-country-code:}") private val fromCountryCode: String
) : SupplierOrderAdapter {

    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }

    @Retry(name = "cj-order")
    @CircuitBreaker(name = "cj-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        // Guard blank credentials (CLAUDE.md #13)
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("CJ API credentials not configured")
        }

        // Build request body, POST to CJ API
        // Parse response using Jackson get() (CLAUDE.md #15)
        // Do NOT catch RestClientException (CLAUDE.md #18)
    }
}
```

Key constraint: **No try-catch for `RestClientException`**. Let it propagate out so `@Retry` and `@CircuitBreaker` intercept it.

#### 5c. StubCjOrderAdapter (local profile)

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/StubCjOrderConfiguration.kt`

```kotlin
@Configuration
@Profile("local")
class StubCjOrderConfiguration {
    @Bean
    fun stubSupplierOrderAdapter(): SupplierOrderAdapter = object : SupplierOrderAdapter {
        override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult =
            SupplierOrderResult(
                supplierOrderId = "stub_cj_${UUID.randomUUID()}",
                status = "CREATED"
            )
    }
}
```

#### 5d. SupplierProductMappingResolver

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/platform/SupplierProductMappingResolver.kt`

Native SQL query against `supplier_product_mappings` table:
```kotlin
@Component
class SupplierProductMappingResolver(
    @PersistenceContext private val entityManager: EntityManager
) {
    fun resolveSupplierVariantId(skuId: UUID, supplier: String): String? {
        val results = entityManager.createNativeQuery(
            """SELECT supplier_variant_id FROM supplier_product_mappings
               WHERE sku_id = :skuId AND supplier = :supplier"""
        ).setParameter("skuId", skuId)
            .setParameter("supplier", supplier)
            .resultList
        return if (results.isNotEmpty()) results.first() as String else null
    }
}
```

### Layer 6: Fulfillment Handler — SupplierOrderPlacementListener

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListener.kt`

```kotlin
@Component
class SupplierOrderPlacementListener(
    private val orderRepository: OrderRepository,
    private val supplierOrderAdapter: SupplierOrderAdapter,
    private val supplierProductMappingResolver: SupplierProductMappingResolver
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        val order = orderRepository.findById(event.orderId.value).orElse(null) ?: return

        // Idempotency guard (NFR-2)
        if (order.supplierOrderId != null) {
            logger.info("Order {} already has supplier order ID, skipping", order.id)
            return
        }

        // Resolve CJ variant ID
        val vid = supplierProductMappingResolver.resolveSupplierVariantId(order.skuId, "CJ_DROPSHIPPING")
        if (vid == null) {
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No CJ product mapping for SKU ${order.skuId}"
            orderRepository.save(order)
            return
        }

        // Build request
        val request = SupplierOrderRequest(
            orderNumber = order.id.toString(),
            shippingAddress = order.shippingAddress,
            products = listOf(SupplierOrderProduct(vid = vid, quantity = order.quantity)),
            logisticName = logisticName,
            fromCountryCode = fromCountryCode
        )

        try {
            val result = supplierOrderAdapter.placeOrder(request)
            order.supplierOrderId = result.supplierOrderId
            orderRepository.save(order)
            logger.info("CJ order placed: internal={}, cj={}", order.id, result.supplierOrderId)
        } catch (e: Exception) {
            logger.error("CJ order failed for order {}: {}", order.id, e.message, e)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = e.message ?: "Unknown CJ API error"
            orderRepository.save(order)
        }
    }
}
```

**Transaction pattern:** `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` per CLAUDE.md #6. The CONFIRMED status is committed before this listener fires. The listener's writes (supplierOrderId or FAILED status) happen in an independent transaction.

**Logistic name and fromCountryCode:** Injected via `@Value` with empty defaults.

### Layer 7: Configuration Changes

**File:** `modules/app/src/main/resources/application.yml`

Add under existing `cj-dropshipping` section:
```yaml
cj-dropshipping:
  api:
    base-url: ${CJ_API_BASE_URL:https://developers.cjdropshipping.com/api2.0/v1}
    access-token: ${CJ_ACCESS_TOKEN:}
  order:
    logistic-name: ${CJ_LOGISTIC_NAME:CJPacket}
    from-country-code: ${CJ_FROM_COUNTRY_CODE:CN}
```

Add Resilience4j instances for `cj-order`:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      cj-order:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
  retry:
    instances:
      cj-order:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
```

### Layer 8: OrderController + DTO Updates

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/dto/OrderResponse.kt`

Add fields to `OrderResponse`:
- `quantity: Int`
- `supplierOrderId: String?`
- `failureReason: String?`
- `shippingCustomerName: String?`
- `shippingCity: String?`
- `shippingCountry: String?`

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/OrderController.kt`

Update `Order.toResponse()` extension to include new fields.

Update `createOrder()` in `OrderController` to include `quantity` in the `CreateOrderCommand`. For the REST API path, quantity comes from the request body (add `quantity: Int` to `CreateOrderRequest`).

---

## Task Breakdown

### Database Migration
- [x] Write `V21__supplier_order_placement.sql` — add `quantity`, shipping address columns, `supplier_order_id`, `failure_reason` to orders; create `supplier_product_mappings` table with unique index on `(sku_id, supplier)`

### Shared Module
- [x] Create `OrderConfirmed` domain event in `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt` — implements `DomainEvent`, carries `orderId: OrderId`, `skuId: SkuId`

### Fulfillment Domain Layer
- [x] Add `FAILED` to `OrderStatus` enum
- [x] Create `ShippingAddress` embeddable (`modules/fulfillment/.../domain/ShippingAddress.kt`) — 10 nullable fields matching CJ + Shopify address model
- [x] Update `Order` entity — add `quantity: Int`, `@Embedded shippingAddress: ShippingAddress`, `supplierOrderId: String?`, `failureReason: String?`; update constructor; update `VALID_TRANSITIONS` to include `CONFIRMED -> FAILED` and `FAILED -> emptySet()`
- [x] Create `ChannelShippingAddress` data class in `ChannelOrder.kt`; add `shippingAddress: ChannelShippingAddress?` to `ChannelOrder`
- [x] Update `CreateOrderCommand` — add `quantity: Int` (required, NO default) and `shippingAddress: ShippingAddress?`

### Fulfillment Domain Service Layer
- [x] Update `ShopifyOrderAdapter.parse()` — extract `shipping_address` from Shopify webhook JSON using `get()` (CLAUDE.md #15); map to `ChannelShippingAddress`; include in returned `ChannelOrder`
- [x] Add `ChannelShippingAddress.toShippingAddress()` mapping extension function
- [x] Update `LineItemOrderCreator.processLineItem()` — pass `quantity = lineItem.quantity` and `shippingAddress` from `channelOrder` into `CreateOrderCommand`
- [x] Update `OrderService.create()` — persist `quantity` and `shippingAddress` from command to Order entity
- [x] Update `OrderService.routeToVendor()` — publish `OrderConfirmed` event after saving CONFIRMED status (same pattern as `markDelivered()` publishing `OrderFulfilled`)
- [x] Add `OrderService.markFailed(orderId, reason)` method — transitions to FAILED, sets failureReason
- [x] Add `OrderService.setSupplierOrderId(orderId, supplierOrderId)` method

### Fulfillment Proxy Layer
- [x] Create `SupplierOrderAdapter` interface + `SupplierOrderRequest`, `SupplierOrderProduct`, `SupplierOrderResult` data classes (`modules/fulfillment/.../proxy/supplier/SupplierOrderAdapter.kt`)
- [x] Create `CjOrderAdapter` implementing `SupplierOrderAdapter` (`@Profile("!local")`, `@Retry("cj-order")`, `@CircuitBreaker("cj-order")`) — maps `SupplierOrderRequest` to CJ `createOrderV2` JSON body; does NOT catch `RestClientException` (CLAUDE.md #18); uses `@Value` with empty defaults (CLAUDE.md #13); uses Jackson `get()` for response parsing (CLAUDE.md #15)
- [x] Create `StubCjOrderConfiguration` (`@Profile("local")`) — returns stub `SupplierOrderResult`
- [x] Create `SupplierProductMappingResolver` — native SQL query against `supplier_product_mappings` by `(sku_id, supplier)`; follows `PlatformListingResolver` pattern

### Fulfillment Handler Layer
- [x] Create `SupplierOrderPlacementListener` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` (CLAUDE.md #6); handles `OrderConfirmed` event; idempotency check via `supplierOrderId != null`; resolves CJ variant ID; calls `SupplierOrderAdapter.placeOrder()`; on success stores CJ order ID; on failure transitions to FAILED with reason
- [x] Update `OrderResponse` DTO — add `quantity`, `supplierOrderId`, `failureReason`, shipping address summary fields
- [x] Update `CreateOrderRequest` DTO — add `quantity: Int`
- [x] Update `OrderController` — thread `quantity` through to `CreateOrderCommand` in REST API path; update `toResponse()` mapping

### Configuration
- [x] Update `application.yml` — add `cj-dropshipping.order.logistic-name` and `cj-dropshipping.order.from-country-code` properties; add `cj-order` Resilience4j circuit breaker + retry instances

### Tests — Unit
- [x] `OrderStatusTest` — verify FAILED is a valid transition from CONFIRMED; verify FAILED is terminal (no transitions out)
- [x] `ShippingAddressTest` — verify embeddable construction and field mapping
- [x] `OrderTest` — verify `updateStatus(FAILED)` works from CONFIRMED; verify `updateStatus(FAILED)` throws from PENDING
- [x] `OrderServiceTest` — test `routeToVendor()` publishes `OrderConfirmed` event; test `markFailed()` transitions and sets failureReason; test `setSupplierOrderId()` persists value
- [x] `CreateOrderCommandTest` — verify quantity is required (compile-time; document in test as assertion that field exists with no default)
- [x] `ShopifyOrderAdapterTest` — test shipping address extraction from webhook JSON; test missing shipping_address field returns null; test all shipping fields map correctly
- [x] `LineItemOrderCreatorTest` — verify quantity from line item flows into CreateOrderCommand (not hardcoded 1); verify shipping address flows through
- [x] `SupplierOrderPlacementListenerTest` — test happy path (OrderConfirmed -> CJ order placed -> supplierOrderId stored); test idempotency (supplierOrderId already set -> skip); test CJ failure (RestClientException -> order FAILED with reason); test missing product mapping (-> order FAILED with reason)
- [x] `CjOrderAdapterTest` — WireMock test verifying request body format matches CJ `createOrderV2` API docs; verify shipping fields mapped correctly; verify quantity flows through; verify `RestClientException` propagates out (NOT caught internally); verify blank credentials throws early

### Tests — Integration
- [x] `OrderLifecycleTest` addition — full flow: create order with quantity=3 and shipping address -> routeToVendor -> OrderConfirmed published -> listener fires -> CJ adapter called with correct quantity and address -> supplierOrderId stored
- [x] `SupplierOrderPlacementIntegrationTest` — test with `TransactionTemplate.execute {}` to trigger AFTER_COMMIT listener; verify end-to-end event -> adapter -> DB update
- [x] `OrderConfirmedEventTest` — verify routeToVendor publishes OrderConfirmed (use `@TransactionalEventListener` test pattern with `TransactionTemplate.execute {}`)
- [x] `QuantityFlowThroughTest` — dedicated test: Shopify webhook JSON with quantity=5 line item -> ShopifyOrderAdapter.parse() -> LineItemOrderCreator -> CreateOrderCommand.quantity == 5 -> Order.quantity == 5 -> SupplierOrderRequest.products[0].quantity == 5

### Tests — Resilience
- [x] `CjOrderAdapterResilienceTest` — verify `@Retry` annotation is present on `placeOrder()`; verify `@CircuitBreaker` annotation is present; verify `RestClientException` is not caught inside the method (WireMock returns 500 -> exception propagates to caller)

---

## Testing Strategy

### Category 1: Data Lineage (Quantity)

Validates that PR #37's hardcoded-quantity bug cannot recur. Tests the complete flow:
- `ChannelLineItem.quantity` (Shopify webhook) -> `CreateOrderCommand.quantity` (no default) -> `Order.quantity` (DB column) -> `SupplierOrderRequest.products[].quantity` -> CJ API request body `products[].quantity`

Key test: `QuantityFlowThroughTest` — end-to-end with quantity=5 (not 1) to catch any hardcoded values.

### Category 2: Data Lineage (Shipping Address)

Validates no field is silently dropped from Shopify webhook to CJ API request:
- Shopify `shipping_address.first_name` + `last_name` -> `shippingCustomerName`
- Shopify `shipping_address.address1` -> CJ `shippingAddress`
- Shopify `shipping_address.city` -> CJ `shippingCity`
- Shopify `shipping_address.province` -> CJ `shippingProvince`
- Shopify `shipping_address.zip` -> CJ `shippingZip`
- Shopify `shipping_address.country` -> CJ `shippingCountry`
- Shopify `shipping_address.country_code` -> CJ `shippingCountryCode`
- Shopify `shipping_address.phone` -> CJ `shippingPhone`

### Category 3: Resilience (CLAUDE.md #18)

Validates that Resilience4j retry/circuit breaker fires correctly:
- `CjOrderAdapter.placeOrder()` does NOT catch `RestClientException`
- WireMock returns HTTP 500 -> exception propagates to caller
- Annotation presence verified reflectively

### Category 4: Error Handling (FAILED State)

Validates graceful degradation:
- CJ API returns error -> order transitions to FAILED, `failureReason` is populated
- Missing CJ product mapping -> order transitions to FAILED with descriptive reason
- FAILED is terminal — no transitions out
- FAILED orders are visible in API responses

### Category 5: Idempotency

Validates NFR-2:
- Listener fires twice for same OrderConfirmed event -> second invocation skips (supplierOrderId already populated)
- CJ API `orderNumber` matches internal order ID for CJ-side deduplication

### Category 6: Transaction Safety (CLAUDE.md #6)

Validates event listener transaction pattern:
- `@TransactionalEventListener(AFTER_COMMIT)` — listener fires only after CONFIRMED is committed
- `@Transactional(REQUIRES_NEW)` — listener writes happen in independent transaction
- CJ failure does not roll back the CONFIRMED status

### Category 7: WireMock Contract Test

Validates CJ API request format against documentation:
- POST to correct endpoint (`/shopping/order/createOrderV2`)
- `CJ-Access-Token` header present
- Request body JSON structure matches CJ docs: `orderNumber`, `shippingZip`, `shippingCountry`, `shippingCountryCode`, `shippingProvince`, `shippingCity`, `shippingPhone`, `shippingCustomerName`, `shippingAddress`, `products[].vid`, `products[].quantity`, `logisticName`, `fromCountryCode`
- WireMock fixtures based on real CJ API documentation (not reverse-engineered from adapter code — per feedback)

---

## Rollout Plan

### Phase 1: Database + Shared Module
1. Apply `V21__supplier_order_placement.sql` migration
2. Deploy `OrderConfirmed` event in shared module

### Phase 2: Domain Changes
3. Deploy `OrderStatus.FAILED`, `ShippingAddress` embeddable, `Order` entity changes
4. Deploy updated `CreateOrderCommand`, `ShopifyOrderAdapter`, `LineItemOrderCreator`, `OrderService`
5. All existing tests must still pass — changes are additive (new fields, new status, new event)

### Phase 3: Supplier Integration
6. Deploy `SupplierOrderAdapter` interface, `CjOrderAdapter`, `StubCjOrderConfiguration`
7. Deploy `SupplierProductMappingResolver`
8. Deploy `SupplierOrderPlacementListener`
9. Update `application.yml` with CJ order config + Resilience4j instances

### Phase 4: Verification
10. Local profile: verify stub adapter fires on order confirmation
11. Seed `supplier_product_mappings` for test SKUs
12. Production: monitor CJ order placement logs (INFO on success, ERROR on failure)
13. Verify FAILED orders surface in API responses

### Rollback Strategy
- The listener is event-driven and behind `@Profile("!local")` adapter. To disable CJ order placement in production without a code change:
  - Set `CJ_ACCESS_TOKEN` to blank — adapter's credential guard returns early with an error that transitions the order to FAILED (visible, not silent)
  - Alternatively, the `SupplierProductMapping` table can be emptied — missing mapping causes FAILED with descriptive reason

---

## Behavioral Consistency Checks

### Check 1: Resilience vs Error Handling (CLAUDE.md #18)
- `CjOrderAdapter.placeOrder()` has `@Retry` + `@CircuitBreaker` -> must NOT catch `RestClientException`
- `SupplierOrderPlacementListener.onOrderConfirmed()` catches `Exception` after retries exhausted -> transitions to FAILED

### Check 2: Transaction Boundaries (CLAUDE.md #6)
- `SupplierOrderPlacementListener` has `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)` -> writes are not silently discarded

### Check 3: Default Values (PR #37 Fix)
- `CreateOrderCommand.quantity: Int` -> NO default value -> compile error if omitted by any caller
- `Order.quantity: Int` -> NO default value in constructor -> must be explicitly provided

### Check 4: Jackson get() vs path() (CLAUDE.md #15)
- `ShopifyOrderAdapter` uses `get()` for shipping address fields -> null-coalescing works correctly
- `CjOrderAdapter` uses `get()` for response parsing -> null-coalescing works correctly

### Check 5: @Value Empty Defaults (CLAUDE.md #13)
- All `@Value` on `CjOrderAdapter` use `${key:}` syntax -> bean instantiates under any profile
- Blank credential guard in `placeOrder()` throws early (not silent return)

### Check 6: Persistable<UUID> (CLAUDE.md #16)
- `Order` already implements `Persistable<UUID>` -> no change needed
- `SupplierProductMapping` is NOT a JPA entity in fulfillment (native SQL access) -> Persistable not applicable
