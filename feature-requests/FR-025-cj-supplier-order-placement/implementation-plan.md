# FR-025: CJ Supplier Order Placement — Implementation Plan

## Technical Design

### Overview

This feature closes the stub gap between "order confirmed" and "purchase order placed with supplier." When `OrderService.routeToVendor()` transitions an order to `CONFIRMED`, it publishes an `OrderConfirmed` domain event. A `@TransactionalEventListener(AFTER_COMMIT)` handler in the fulfillment module consumes the event and places a purchase order with CJ Dropshipping via a `SupplierOrderAdapter` interface. On success, the CJ order ID is stored on the internal `Order`. On failure, the order transitions to `FAILED` with a reason.

### Data Flow

```
Shopify webhook (with shipping address)
  -> ShopifyOrderAdapter.parse() extracts ChannelShippingAddress
  -> LineItemOrderCreator creates Order with quantity + shipping address
  -> OrderService.routeToVendor() transitions to CONFIRMED, publishes OrderConfirmed
  -> SupplierOrderPlacementListener (AFTER_COMMIT + REQUIRES_NEW) handles event
  -> SupplierOrderPlacementService resolves CJ variant ID, calls CjSupplierOrderAdapter
  -> CJ API returns order ID -> stored on Order.supplierOrderId
  -> CJ API returns error -> Order transitions to FAILED with failureReason
```

### Key Design Decisions

#### Decision 1: `vid` Gap — New `supplier_product_mappings` Table

**Problem:** The demand scan stores `cj_pid` (product ID) in `demand_signals` JSONB but not `vid` (variant ID). CJ's `createOrderV2` requires `vid`.

**Decision:** Create a `supplier_product_mappings` table that maps `(sku_id, supplier_type)` to `(supplier_product_id, supplier_variant_id)`. Populated at SKU listing time (when the operator maps an internal SKU to a CJ product variant). This is a persistent, queryable mapping — not buried in JSONB.

**Rationale:** Modifying the demand scan to also fetch `vid` is fragile: CJ products have multiple variants, and the correct variant depends on the specific SKU configuration (size, color). The mapping must be explicit and editable. A dedicated table also supports future suppliers (Printful, Printify) without modifying the demand scan pipeline.

#### Decision 2: No `SUPPLIER_PENDING` Intermediate State

**Decision:** Go directly from `CONFIRMED` to `FAILED` on CJ rejection. Do not add a `SUPPLIER_PENDING` state.

**Rationale:** The supplier order placement happens synchronously within the event listener's `REQUIRES_NEW` transaction. The flow is: receive event -> call CJ API -> update order (store `supplierOrderId` or transition to `FAILED`). There is no async polling loop that needs an intermediate state. Adding `SUPPLIER_PENDING` would require a second scheduled job to poll CJ for confirmation, which is unnecessary — CJ's `createOrderV2` returns success/failure synchronously.

#### Decision 3: `ShippingAddress` as `@Embeddable` on Order

**Decision:** Add `ShippingAddress` as an `@Embeddable` class with columns directly on the `orders` table, following the `ShipmentDetails` pattern already established.

**Rationale:** Shipping address is a value object with 1:1 cardinality to an order. The `ShipmentDetails` embeddable pattern is already proven in this codebase. A separate entity/table adds join complexity with no benefit.

#### Decision 4: Quantity from ChannelLineItem, No Default

**Decision:** Add `quantity: Int` to `Order` entity and `CreateOrderCommand`. The quantity flows from `ChannelLineItem.quantity` through `LineItemOrderCreator`. No default value — the field is required.

**Rationale:** PR #37 hardcoded `quantity = 1`, which was a documented failure. The quantity must be explicit from the source (Shopify line item) all the way through to the CJ API.

#### Decision 5: Auto-Confirm After Order Creation

**Decision:** After `LineItemOrderCreator` creates an order from a Shopify webhook, it calls `orderService.routeToVendor()` to transition to `CONFIRMED` immediately. Shopify orders are pre-paid — there is no reason to leave them in `PENDING`.

**Rationale:** The current flow creates orders in `PENDING` and requires a separate manual `POST /{id}/confirm` call. For Shopify webhook orders, the payment is already captured by Shopify before the webhook fires. Auto-confirming closes the gap so the `OrderConfirmed` event triggers supplier order placement without manual intervention.

#### Decision 6: Idempotency via `supplierOrderId` Field

**Decision:** The supplier order placement listener checks `order.supplierOrderId` before calling CJ. If already populated, skip. This handles duplicate event delivery (retries, at-least-once semantics).

**Rationale:** Spec NFR-5 requires idempotency. The `supplierOrderId` field is the natural deduplication signal — if it's non-null, the supplier order was already placed.

## Architecture Decisions

### Module Boundaries

All new code lives in the `fulfillment` module except:
- `OrderConfirmed` domain event: `shared` module (cross-module event contract)
- Flyway migration: `app` module (where all migrations live)

The new `CjSupplierOrderAdapter` is in `fulfillment`, not `portfolio`. The existing `CjDropshippingAdapter` in `portfolio` is a `DemandSignalProvider` for product discovery — a fundamentally different concern. Supplier order placement is a fulfillment concern.

### Hexagonal Architecture Layers

| Layer | New Components |
|---|---|
| **Shared** | `OrderConfirmed` domain event |
| **Domain** | `ShippingAddress` embeddable, `OrderStatus.FAILED`, `SupplierOrderPlacementService` |
| **Proxy** | `SupplierOrderAdapter` interface, `CjSupplierOrderAdapter`, `SupplierProductMappingResolver` |
| **Handler** | `SupplierOrderPlacementListener` (event listener) |
| **Persistence** | `V21` migration (order columns + supplier_product_mappings table) |

### Transaction Strategy

```
routeToVendor() [TX-1: existing transaction]
  -> order.updateStatus(CONFIRMED)
  -> eventPublisher.publishEvent(OrderConfirmed(...))
  -> TX-1 commits

SupplierOrderPlacementListener.onOrderConfirmed() [TX-2: REQUIRES_NEW]
  -> load Order
  -> check supplierOrderId (idempotency guard)
  -> resolve CJ variant ID from supplier_product_mappings
  -> call CjSupplierOrderAdapter.placeOrder()
  -> on success: order.supplierOrderId = cjOrderId
  -> on failure: order.updateStatus(FAILED), order.failureReason = reason
  -> TX-2 commits independently
```

## Layer-by-Layer Implementation

### Layer 1: Shared Module — Domain Event

**File:** `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt`

```kotlin
data class OrderConfirmed(
    val orderId: OrderId,
    val skuId: SkuId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
```

Follows the `OrderFulfilled` pattern exactly.

### Layer 2: Domain — Order Entity Changes

**Modified:** `Order.kt`, `OrderStatus.kt`, `CreateOrderCommand.kt`

**New:** `ShippingAddress.kt` (embeddable)

#### OrderStatus.FAILED

Add `FAILED` to the enum. Add transitions:
- `CONFIRMED -> FAILED` (supplier rejection)
- `PENDING -> FAILED` (future use: payment failure)

#### Order Entity New Fields

| Field | Type | Column | Nullable | Notes |
|---|---|---|---|---|
| `quantity` | `Int` | `quantity` | NOT NULL | No default — explicit from source |
| `supplierOrderId` | `String?` | `supplier_order_id` | YES | CJ order ID on success |
| `failureReason` | `String?` | `failure_reason` | YES | Error message on FAILED |
| `shippingAddress` | `ShippingAddress` | embedded columns | YES (embeddable) | All fields nullable — not every order has shipping yet |

#### ShippingAddress Embeddable

```kotlin
@Embeddable
class ShippingAddress(
    @Column(name = "shipping_customer_name") var customerName: String? = null,
    @Column(name = "shipping_address_line1") var addressLine1: String? = null,
    @Column(name = "shipping_address_line2") var addressLine2: String? = null,
    @Column(name = "shipping_city") var city: String? = null,
    @Column(name = "shipping_province") var province: String? = null,
    @Column(name = "shipping_province_code") var provinceCode: String? = null,
    @Column(name = "shipping_country") var country: String? = null,
    @Column(name = "shipping_country_code") var countryCode: String? = null,
    @Column(name = "shipping_zip") var zip: String? = null,
    @Column(name = "shipping_phone") var phone: String? = null
)
```

Follows `ShipmentDetails` embeddable pattern: all fields have defaults (nullable) so JPA can instantiate without arguments. Includes `provinceCode` for CJ's `shippingProvince` field (which actually expects the province code, not full name).

#### CreateOrderCommand Extension

Add `quantity: Int` and `shippingAddress: ShippingAddress?` to `CreateOrderCommand`.

### Layer 3: Domain — ChannelOrder Extension

**Modified:** `ChannelOrder.kt`

Add `ChannelShippingAddress` data class and `shippingAddress: ChannelShippingAddress?` field to `ChannelOrder`:

```kotlin
data class ChannelShippingAddress(
    val firstName: String?,
    val lastName: String?,
    val address1: String?,
    val address2: String?,
    val city: String?,
    val province: String?,
    val provinceCode: String?,
    val country: String?,
    val countryCode: String?,
    val zip: String?,
    val phone: String?
)
```

### Layer 4: Proxy — ShopifyOrderAdapter Shipping Address Extraction

**Modified:** `ShopifyOrderAdapter.kt`

Extract the `shipping_address` object from the Shopify webhook payload. Every field uses the NullNode guard per CLAUDE.md #17:

```kotlin
val addrNode = root.get("shipping_address")
val shippingAddress = if (addrNode != null && !addrNode.isNull) {
    ChannelShippingAddress(
        firstName = addrNode.get("first_name")?.let { if (!it.isNull) it.asText() else null },
        lastName = addrNode.get("last_name")?.let { if (!it.isNull) it.asText() else null },
        address1 = addrNode.get("address1")?.let { if (!it.isNull) it.asText() else null },
        address2 = addrNode.get("address2")?.let { if (!it.isNull) it.asText() else null },
        city = addrNode.get("city")?.let { if (!it.isNull) it.asText() else null },
        province = addrNode.get("province")?.let { if (!it.isNull) it.asText() else null },
        provinceCode = addrNode.get("province_code")?.let { if (!it.isNull) it.asText() else null },
        country = addrNode.get("country")?.let { if (!it.isNull) it.asText() else null },
        countryCode = addrNode.get("country_code")?.let { if (!it.isNull) it.asText() else null },
        zip = addrNode.get("zip")?.let { if (!it.isNull) it.asText() else null },
        phone = addrNode.get("phone")?.let { if (!it.isNull) it.asText() else null }
    )
} else null
```

Critical: ALL 11 fields use the identical NullNode guard. PR #39 failed because 9/10 fields used bare `?.asText()`.

### Layer 5: Domain Service — LineItemOrderCreator Update

**Modified:** `LineItemOrderCreator.kt`

1. Accept `shippingAddress: ChannelShippingAddress?` parameter (from `ChannelOrder.shippingAddress`)
2. Map `ChannelShippingAddress` to `ShippingAddress` (domain embeddable):
   - `customerName` = `"${firstName ?: ""} ${lastName ?: ""}".trim()`
   - Other fields map 1:1
3. Pass `quantity` from `lineItem.quantity` to `CreateOrderCommand`
4. Pass `shippingAddress` to `CreateOrderCommand`
5. After creating the order and setting channel metadata, call `orderService.routeToVendor(order.id)` to auto-confirm Shopify orders

### Layer 6: Domain Service — OrderService.routeToVendor() Event Publishing

**Modified:** `OrderService.kt`

Add `eventPublisher.publishEvent(OrderConfirmed(...))` after saving the CONFIRMED order, before returning. Follows the `markDelivered()` pattern which publishes `OrderFulfilled`:

```kotlin
fun routeToVendor(orderId: UUID): Order {
    // ... existing code ...
    order.updateStatus(OrderStatus.CONFIRMED)
    val saved = orderRepository.save(order)
    eventPublisher.publishEvent(
        OrderConfirmed(
            orderId = order.orderId(),
            skuId = order.skuId()
        )
    )
    logger.info("Order {} routed to vendor {}, status -> CONFIRMED", orderId, order.vendorId)
    return saved
}
```

### Layer 7: Domain Service — SupplierOrderPlacementService

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/SupplierOrderPlacementService.kt`

Orchestrates the supplier order placement:

```kotlin
@Service
class SupplierOrderPlacementService(
    private val orderRepository: OrderRepository,
    private val supplierOrderAdapter: SupplierOrderAdapter,
    private val supplierProductMappingResolver: SupplierProductMappingResolver
) {
    fun placeSupplierOrder(orderId: UUID) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }

        // Idempotency: skip if already placed
        if (order.supplierOrderId != null) {
            logger.info("Order {} already has supplier order {}, skipping", orderId, order.supplierOrderId)
            return
        }

        // Resolve supplier variant mapping
        val mapping = supplierProductMappingResolver.resolve(order.skuId)
        if (mapping == null) {
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No supplier product mapping found for SKU ${order.skuId}"
            orderRepository.save(order)
            return
        }

        val request = SupplierOrderRequest(
            orderId = order.id,
            shippingAddress = order.shippingAddress,
            supplierProductId = mapping.supplierProductId,
            supplierVariantId = mapping.supplierVariantId,
            quantity = order.quantity
        )

        val result = supplierOrderAdapter.placeOrder(request)

        when (result) {
            is SupplierOrderResult.Success -> {
                order.supplierOrderId = result.supplierOrderId
                orderRepository.save(order)
            }
            is SupplierOrderResult.Failure -> {
                order.updateStatus(OrderStatus.FAILED)
                order.failureReason = result.reason
                orderRepository.save(order)
            }
        }
    }
}
```

### Layer 8: Proxy — SupplierOrderAdapter Interface

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierOrderAdapter.kt`

```kotlin
interface SupplierOrderAdapter {
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderId: UUID,
    val shippingAddress: ShippingAddress,
    val supplierProductId: String,
    val supplierVariantId: String,
    val quantity: Int
)

sealed class SupplierOrderResult {
    data class Success(val supplierOrderId: String) : SupplierOrderResult()
    data class Failure(val reason: String) : SupplierOrderResult()
}
```

### Layer 9: Proxy — CjSupplierOrderAdapter

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjSupplierOrderAdapter.kt`

Follows `StripeRefundAdapter` pattern: `@Component @Profile("!local")`, `@Value` with empty defaults, credential guard, `@CircuitBreaker`, `@Retry`.

Key implementation details:
- Endpoint: `POST /api2.0/v1/shopping/order/createOrderV2`
- Auth: `CJ-Access-Token` header
- Request body: JSON (not form-encoded), so Jackson serialization handles escaping (NFR-4)
- Response parsing: `get()` with NullNode guard on all fields (CLAUDE.md #15, #17)
- The adapter returns `SupplierOrderResult` (sealed class) — it does NOT catch exceptions that Resilience4j needs (lesson from PR #37)
- HTTP/network errors propagate to Resilience4j; only CJ business errors (error code in response) are mapped to `SupplierOrderResult.Failure`

```kotlin
@Component
@Profile("!local")
class CjSupplierOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String
) : SupplierOrderAdapter {

    @CircuitBreaker(name = "cj-supplier-order")
    @Retry(name = "cj-supplier-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            logger.warn("CJ API credentials blank — cannot place order")
            return SupplierOrderResult.Failure("CJ API credentials not configured")
        }

        val cjRequest = buildCjOrderRequest(request)
        val response = restClient.post()
            .uri("/api2.0/v1/shopping/order/createOrderV2")
            .header("CJ-Access-Token", accessToken)
            .header("Content-Type", "application/json")
            .body(objectMapper.writeValueAsString(cjRequest))
            .retrieve()
            .body(JsonNode::class.java)

        // Parse response with NullNode guard
        val result = response?.get("result")?.let { if (!it.isNull) it.asText() else null }
        val code = response?.get("code")?.let { if (!it.isNull) it.asInt() else null } ?: -1
        val message = response?.get("message")?.let { if (!it.isNull) it.asText() else null } ?: "Unknown error"

        if (result != null && code == 200) {
            val orderId = response.get("data")?.get("orderId")
                ?.let { if (!it.isNull) it.asText() else null }
                ?: return SupplierOrderResult.Failure("CJ returned success but no orderId in response")
            return SupplierOrderResult.Success(orderId)
        }

        return SupplierOrderResult.Failure("CJ API error (code=$code): $message")
    }
}
```

CJ request body structure (from CJ API docs):
```json
{
  "orderNumber": "<internal-order-id>",
  "shippingCountryCode": "US",
  "shippingCountry": "United States",
  "shippingCustomerName": "John Doe",
  "shippingAddress": "123 Main St Apt 4",
  "shippingCity": "Los Angeles",
  "shippingProvince": "CA",
  "shippingZip": "90001",
  "shippingPhone": "5551234567",
  "fromCountryCode": "CN",
  "products": [
    {
      "vid": "<variant-id>",
      "quantity": 2
    }
  ]
}
```

### Layer 10: Proxy — SupplierProductMappingResolver

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/SupplierProductMappingResolver.kt`

Cross-module read-only access via `EntityManager` (follows `PlatformListingResolver` pattern):

```kotlin
@Component
class SupplierProductMappingResolver(
    @PersistenceContext private val entityManager: EntityManager
) {
    fun resolve(skuId: UUID): SupplierProductMapping? {
        val results = entityManager.createNativeQuery(
            """SELECT supplier_product_id, supplier_variant_id
               FROM supplier_product_mappings
               WHERE sku_id = :skuId AND supplier_type = 'CJ_DROPSHIPPING'"""
        ).setParameter("skuId", skuId).resultList

        if (results.isEmpty()) return null
        val row = results.first() as Array<*>
        return SupplierProductMapping(
            supplierProductId = row[0] as String,
            supplierVariantId = row[1] as String
        )
    }
}

data class SupplierProductMapping(
    val supplierProductId: String,
    val supplierVariantId: String
)
```

### Layer 11: Handler — Event Listener

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/handler/SupplierOrderPlacementListener.kt`

```kotlin
@Component
class SupplierOrderPlacementListener(
    private val supplierOrderPlacementService: SupplierOrderPlacementService
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        logger.info("OrderConfirmed received for order {}, placing supplier order", event.orderId)
        supplierOrderPlacementService.placeSupplierOrder(event.orderId.value)
    }
}
```

Per CLAUDE.md #6: `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`. The listener is thin — delegates to `SupplierOrderPlacementService` for testability.

### Layer 12: Persistence — Flyway Migration V21

**New file:** `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql`

```sql
-- FR-025: CJ Supplier Order Placement

-- Add quantity field to orders (required, no default)
ALTER TABLE orders ADD COLUMN quantity INT;
UPDATE orders SET quantity = 1 WHERE quantity IS NULL;
ALTER TABLE orders ALTER COLUMN quantity SET NOT NULL;

-- Add supplier order tracking fields
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason TEXT;

-- Add shipping address fields (embedded on orders)
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address_line1 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_address_line2 VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_country VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country_code VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip VARCHAR(20);
ALTER TABLE orders ADD COLUMN shipping_phone VARCHAR(50);

-- Supplier product mapping table
CREATE TABLE supplier_product_mappings (
    id                   UUID PRIMARY KEY,
    sku_id               UUID NOT NULL REFERENCES skus(id),
    supplier_type        VARCHAR(50) NOT NULL,
    supplier_product_id  VARCHAR(255) NOT NULL,
    supplier_variant_id  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mappings_sku_supplier
    ON supplier_product_mappings(sku_id, supplier_type);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);

-- Index for supplier order lookups
CREATE INDEX idx_orders_supplier_order_id ON orders(supplier_order_id);

-- Add FAILED to valid order statuses (enforced at application level, not DB constraint)
```

Note: The `quantity` migration sets existing rows to 1 as a data migration for backwards compatibility, then makes the column NOT NULL. All new orders will have explicit quantities from the source.

### Layer 13: Stub Configuration for Local Profile

**New file:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/StubSupplierOrderConfiguration.kt`

```kotlin
@Configuration
@Profile("local")
class StubSupplierOrderConfiguration {
    @Bean
    fun stubSupplierOrderAdapter(): SupplierOrderAdapter = object : SupplierOrderAdapter {
        override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult =
            SupplierOrderResult.Success("stub_cj_order_${UUID.randomUUID()}")
    }
}
```

Follows `StubRefundConfiguration` pattern.

### Layer 14: OrderResponse DTO Update

**Modified:** `OrderResponse` and `OrderController.toResponse()` to include new fields: `quantity`, `supplierOrderId`, `failureReason`, shipping address fields.

## Task Breakdown

### Shared Module

- [x] **Task 1.1:** Create `OrderConfirmed` domain event in `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt` — follows `OrderFulfilled` pattern (orderId, skuId, occurredAt)

### Persistence Layer

- [x] **Task 2.1:** Write `V21__supplier_order_placement.sql` migration — `quantity` column, `supplier_order_id`, `failure_reason`, 10 shipping address columns on `orders`, `supplier_product_mappings` table with unique index on `(sku_id, supplier_type)`

### Domain Layer

- [x] **Task 3.1:** Add `FAILED` to `OrderStatus` enum
- [x] **Task 3.2:** Add `CONFIRMED -> FAILED` and `PENDING -> FAILED` transitions to `Order.VALID_TRANSITIONS`, add `FAILED -> emptySet()` terminal state
- [x] **Task 3.3:** Create `ShippingAddress` `@Embeddable` class with 10 nullable fields (customerName, addressLine1, addressLine2, city, province, provinceCode, country, countryCode, zip, phone)
- [x] **Task 3.4:** Add `quantity: Int`, `supplierOrderId: String?`, `failureReason: String?`, `shippingAddress: ShippingAddress` fields to `Order` entity
- [x] **Task 3.5:** Add `quantity: Int`, `shippingAddress: ShippingAddress?` to `CreateOrderCommand`
- [x] **Task 3.6:** Add `ChannelShippingAddress` data class to `ChannelOrder.kt`, add `shippingAddress: ChannelShippingAddress?` field to `ChannelOrder`
- [x] **Task 3.7:** Create `SupplierOrderPlacementService` — loads order, idempotency check on `supplierOrderId`, resolves mapping, calls adapter, stores result or transitions to FAILED

### Proxy Layer

- [x] **Task 4.1:** Create `SupplierOrderAdapter` interface, `SupplierOrderRequest` data class, `SupplierOrderResult` sealed class in `modules/fulfillment/.../proxy/supplier/`
- [x] **Task 4.2:** Create `CjSupplierOrderAdapter` — `@Component @Profile("!local")`, `@Value` with empty defaults, credential guard, `@CircuitBreaker`, `@Retry`, JSON request body, NullNode guard on all response fields
- [x] **Task 4.3:** Create `StubSupplierOrderConfiguration` — `@Configuration @Profile("local")`, stub adapter returning success
- [x] **Task 4.4:** Create `SupplierProductMappingResolver` — `EntityManager` native query against `supplier_product_mappings`, follows `PlatformListingResolver` pattern
- [x] **Task 4.5:** Update `ShopifyOrderAdapter.parse()` — extract `shipping_address` from webhook payload with NullNode guard on ALL 11 fields, return `ChannelShippingAddress` on `ChannelOrder`

### Handler/Service Layer

- [x] **Task 5.1:** Update `OrderService.routeToVendor()` — publish `OrderConfirmed` event after saving CONFIRMED status (follows `markDelivered()` pattern)
- [x] **Task 5.2:** Create `SupplierOrderPlacementListener` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`, delegates to `SupplierOrderPlacementService`
- [x] **Task 5.3:** Update `LineItemOrderCreator.processLineItem()` — accept `ChannelShippingAddress?`, map to `ShippingAddress`, pass `quantity` and `shippingAddress` to `CreateOrderCommand`, call `routeToVendor()` after order creation
- [x] **Task 5.4:** Update `ShopifyOrderProcessingService.onOrderReceived()` — pass `channelOrder.shippingAddress` to `LineItemOrderCreator.processLineItem()`
- [x] **Task 5.5:** Update `OrderService.create()` — persist `quantity` and `shippingAddress` from `CreateOrderCommand` onto `Order`
- [x] **Task 5.6:** Update `OrderResponse` DTO and `OrderController.toResponse()` — include `quantity`, `supplierOrderId`, `failureReason`, shipping address fields

### Test Layer

- [x] **Task 6.1:** Unit test `Order.updateStatus()` — verify `CONFIRMED -> FAILED` transition works, `FAILED -> CONFIRMED` throws, `PENDING -> FAILED` works
- [x] **Task 6.2:** Unit test `SupplierOrderPlacementService` — mock `SupplierOrderAdapter` and `SupplierProductMappingResolver`:
  - Happy path: adapter returns Success -> `supplierOrderId` stored on order
  - Failure path: adapter returns Failure -> order transitions to FAILED with reason
  - Idempotency: order already has `supplierOrderId` -> adapter never called
  - Missing mapping: resolver returns null -> order transitions to FAILED
- [x] **Task 6.3:** Unit test `ShopifyOrderAdapter` shipping address extraction:
  - Full address: all fields present -> all extracted correctly
  - Null fields: JSON `null` values in address fields -> Kotlin `null` (not string "null")
  - Missing shipping_address node: -> `shippingAddress` is null on ChannelOrder
  - Mixed null/present: some fields null, some present -> correct mapping
- [x] **Task 6.4:** WireMock contract test `CjSupplierOrderAdapter`:
  - Successful order placement: CJ returns `code=200` with `data.orderId` -> `SupplierOrderResult.Success`
  - Out of stock error: CJ returns error code -> `SupplierOrderResult.Failure` with reason
  - Invalid address error: CJ returns validation error -> `SupplierOrderResult.Failure`
  - Auth failure: 401 response -> exception propagates to Resilience4j (NOT caught by adapter)
  - Credential guard: blank credentials -> `Failure` returned without HTTP call
  - Request body verification: assert correct JSON structure sent to CJ (orderNumber, shipping fields, products array with vid and quantity)
- [x] **Task 6.5:** Unit test `LineItemOrderCreator` — verify quantity from lineItem flows to CreateOrderCommand, verify shippingAddress mapping from ChannelShippingAddress to ShippingAddress, verify routeToVendor called after creation
- [x] **Task 6.6:** Integration test — full chain:
  - Order created with shipping address and quantity -> routeToVendor -> OrderConfirmed published -> listener fires -> CJ adapter called (WireMock) -> supplierOrderId stored
  - Same flow but CJ returns error -> order marked FAILED with failureReason
- [x] **Task 6.7:** Unit test `OrderService.routeToVendor()` — verify `OrderConfirmed` event published after CONFIRMED transition
- [x] **Task 6.8:** Existing test fixes — update `OrderLifecycleTest`, `OrderServiceTest`, `ShopifyOrderProcessingServiceTest` to handle new `quantity` field and `OrderConfirmed` event

## Testing Strategy

### Principle: No Test Theater (NFR-8)

Every test calls production code and asserts on its output. Tests that would have been theater in PR #39:
- Asserting `true` -> assert on actual field values
- Checking fixture content with `contains()` -> verify production adapter produces correct request body via WireMock request matching
- Verifying data class constructors -> verify service behavior through full call chain

### Test Categories

| Category | What It Tests | Tools |
|---|---|---|
| Unit | `SupplierOrderPlacementService`, `Order` state transitions, `ShopifyOrderAdapter` address parsing | Mockito, JUnit 5 |
| Contract | `CjSupplierOrderAdapter` HTTP interaction | WireMock, JUnit 5 |
| Integration | Full event-driven chain (create -> confirm -> event -> supplier order) | Spring test context, WireMock, `TransactionTemplate` |

### WireMock Fixture Requirements (NFR-7)

CJ API fixtures must be based on https://developers.cjdropshipping.com/ documentation:

1. **Success response:** `{ "result": true, "code": 200, "message": "success", "data": { "orderId": "..." } }`
2. **Out of stock:** `{ "result": false, "code": 1600501, "message": "product out of stock" }`
3. **Invalid address:** `{ "result": false, "code": 1600502, "message": "invalid shipping address" }`
4. **Auth failure:** HTTP 401 with `{ "result": false, "code": 401, "message": "Unauthorized" }`

### Boundary Tests (NFR-2)

ShopifyOrderAdapter test must include a payload with JSON `null` shipping address fields:
```json
{
  "shipping_address": {
    "first_name": "John",
    "last_name": null,
    "address1": "123 Main St",
    "address2": null,
    "city": "LA",
    "province": null,
    "province_code": "CA",
    "country": "United States",
    "country_code": "US",
    "zip": "90001",
    "phone": null
  }
}
```

Assert: `lastName` is Kotlin `null`, NOT string `"null"`. This is the exact bug from PR #39.

### TransactionalEventListener Testing (CLAUDE.md testing convention)

The integration test for `SupplierOrderPlacementListener` must publish `OrderConfirmed` inside `TransactionTemplate.execute {}` so the `AFTER_COMMIT` listener actually fires.

## Rollout Plan

### Implementation Order (Dependency-Sorted)

1. **V21 migration** (Task 2.1) — schema must exist before entity changes compile against DB
2. **Shared event** (Task 1.1) — `OrderConfirmed` needed by both publisher and listener
3. **Domain model** (Tasks 3.1-3.6) — `OrderStatus.FAILED`, `ShippingAddress`, Order entity changes, `CreateOrderCommand`, `ChannelOrder`
4. **Proxy interfaces** (Tasks 4.1, 4.4) — `SupplierOrderAdapter` interface, `SupplierProductMappingResolver`
5. **CJ adapter** (Tasks 4.2, 4.3) — `CjSupplierOrderAdapter` + stub
6. **Shopify adapter** (Task 4.5) — shipping address extraction
7. **Domain service** (Task 3.7) — `SupplierOrderPlacementService`
8. **Handler/service updates** (Tasks 5.1-5.6) — event publishing, listener, LineItemOrderCreator, OrderService.create(), DTO
9. **Tests** (Tasks 6.1-6.8) — unit, contract, integration
10. **Existing test fixes** (Task 6.8) — update tests broken by new required `quantity` field

### Risk Mitigations

| Risk | Mitigation |
|---|---|
| NullNode trap recurrence (PR #39) | Spec requires guard on ALL fields; test includes JSON null boundary case; code review checklist item |
| Hardcoded quantity recurrence (PR #37) | `quantity` is required on `CreateOrderCommand` (no default); compilation enforces it |
| Resilience4j swallowed by catch (PR #37) | Adapter only catches CJ business errors from response body; HTTP/network exceptions propagate |
| Test theater recurrence (PR #39) | Every test assertion operates on production code output; no `assert(true)` or fixture content checks |
| Existing tests broken by new `quantity` field | Task 6.8 explicitly updates all existing test files that construct `Order` or `CreateOrderCommand` |
