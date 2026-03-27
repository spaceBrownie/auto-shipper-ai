# FR-025: CJ Supplier Order Placement -- Implementation Plan

## Technical Design

### Architecture Overview

When `OrderService.routeToVendor()` transitions an order to `CONFIRMED`, it publishes an `OrderConfirmed` domain event. A `@TransactionalEventListener(AFTER_COMMIT)` listener (`SupplierOrderPlacementListener`) picks up the event in a `REQUIRES_NEW` transaction, resolves the SKU's CJ variant ID via `SupplierProductMappingRepository`, and delegates to a `SupplierOrderAdapter` implementation (`CjOrderAdapter`) to place the purchase order via CJ's `createOrderV2` API. On success, the CJ order ID is stored on the `Order` entity. On failure, the order transitions to `FAILED` with a categorized failure reason.

```
OrderService.routeToVendor()
  |
  +--> Order status: PENDING -> CONFIRMED
  +--> Publish OrderConfirmed event
  |
  (transaction commits)
  |
  +--> SupplierOrderPlacementListener.onOrderConfirmed()   [REQUIRES_NEW tx]
         |
         +--> Check idempotency: supplierOrderId already set? -> skip
         +--> Resolve CJ vid: SupplierProductMappingRepository.findBySkuId()
         +--> Build SupplierOrderRequest (address, vid, quantity, orderNumber)
         +--> CjOrderAdapter.placeOrder(request)
         |      |
         |      +--> POST /api2.0/v1/shopping/order/createOrderV2
         |      +--> Inspect JSON code field (HTTP 200 for all responses)
         |      +--> Return SupplierOrderResult (success w/ orderId, or failure w/ reason)
         |
         +--> Success: order.supplierOrderId = cjOrderId
         +--> Failure: order.updateStatus(FAILED), order.failureReason = categorized reason
```

### Component Interactions

| Component | Module | Responsibility |
|---|---|---|
| `OrderConfirmed` | shared | Domain event carrying orderId, skuId |
| `SupplierOrderPlacementListener` | fulfillment | Event listener, orchestrates placement |
| `SupplierOrderAdapter` | fulfillment | Interface: `placeOrder(request): SupplierOrderResult` |
| `CjOrderAdapter` | fulfillment | CJ HTTP client implementation (`@Profile("!local")`) |
| `StubSupplierOrderAdapter` | fulfillment | Stub for local dev (`@Profile("local")`) |
| `SupplierProductMapping` | fulfillment | JPA entity mapping sku_id to supplier variant |
| `SupplierProductMappingRepository` | fulfillment | JPA repository for mapping lookups |
| `ShippingAddress` | fulfillment | Embeddable on Order entity |
| `ChannelShippingAddress` | fulfillment | Data class on ChannelOrder for parsed address |

## Architecture Decisions

### 1. Event-driven placement (not synchronous call from routeToVendor)

Supplier order placement runs after the CONFIRMED transaction commits. This ensures the order is durably persisted before contacting the external API. If the CJ API is slow or fails, the original order confirmation is not affected. This follows CLAUDE.md constraint #6: `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`.

### 2. ShippingAddress as @Embeddable (not separate entity)

A shipping address is a value object belonging to exactly one order -- no independent lifecycle, no sharing between orders. An `@Embeddable` maps to columns on the `orders` table, avoiding a join. This mirrors the existing `ShipmentDetails` pattern on the `Order` entity.

### 3. SupplierProductMapping as a separate entity (not JSONB on demand_candidates)

The `demand_candidates` table stores `cj_pid` in its `demand_signals` JSONB, but: (a) not every demand candidate becomes a SKU, (b) the demand scan pipeline discovers `cj_pid` (product ID), not `vid` (variant ID) -- the `vid` requires a separate product detail API call, and (c) order placement needs fast lookup by `sku_id`. A dedicated `supplier_product_mappings` table with a unique index on `(sku_id, supplier)` provides clean, indexed access.

### 4. SupplierOrderAdapter interface (not CJ-specific service)

The spec requires supporting multiple suppliers (Printful, Gelato). The interface takes a supplier-agnostic `SupplierOrderRequest` and returns a `SupplierOrderResult`. The listener resolves which adapter to use based on the supplier field in the product mapping. Phase 1 has only `CjOrderAdapter`.

### 5. Failure categorization (not raw API dumps)

The `failureReason` on the Order entity stores categorized enum-like strings (`OUT_OF_STOCK`, `INVALID_ADDRESS`, `API_AUTH_FAILURE`, `NETWORK_ERROR`, `UNKNOWN`) parsed from CJ's response. This enables automated retry logic for transient failures and operational dashboards for permanent failures.

### 6. Idempotency via internal order ID as orderNumber

The internal `order.id` (UUID) is used as the `orderNumber` in the CJ API request. Combined with a pre-check of `order.supplierOrderId != null`, this provides two layers of protection against duplicate supplier orders: (a) the listener skips if a supplier order ID is already recorded, and (b) CJ's own idempotency on `orderNumber` prevents duplicate placement even if the check races.

### 7. Shipping address passed via ChannelOrder (not separate event)

The `ShopifyOrderAdapter.parse()` method is extended to extract `shipping_address` from the webhook JSON and include it in `ChannelOrder` as a `ChannelShippingAddress` data class. `LineItemOrderCreator` then passes it through to `CreateOrderCommand`, which stores it on the `Order` entity. This keeps the existing data flow intact -- no new event needed for address capture.

## Layer-by-Layer Implementation

### Layer 1: Shared Module -- OrderConfirmed Event

**File:** `modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt`

A new domain event following the existing `OrderFulfilled` pattern:

```kotlin
data class OrderConfirmed(
    val orderId: OrderId,
    val skuId: SkuId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
```

Minimal: carries only `orderId` and `skuId`. The listener loads the full `Order` from the database in its own transaction.

### Layer 2: Fulfillment Domain -- Order Entity Changes

#### 2a. ShippingAddress Embeddable

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/ShippingAddress.kt`

```kotlin
@Embeddable
class ShippingAddress(
    @Column(name = "shipping_customer_name") var customerName: String? = null,
    @Column(name = "shipping_address")       var address: String? = null,
    @Column(name = "shipping_city")          var city: String? = null,
    @Column(name = "shipping_province")      var province: String? = null,
    @Column(name = "shipping_country")       var country: String? = null,
    @Column(name = "shipping_country_code")  var countryCode: String? = null,
    @Column(name = "shipping_zip")           var zip: String? = null,
    @Column(name = "shipping_phone")         var phone: String? = null
)
```

Follows the `ShipmentDetails` embeddable pattern. All fields nullable to handle partial addresses from webhooks. Column names match CJ's API field naming for straightforward mapping.

#### 2b. OrderStatus.FAILED

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/OrderStatus.kt`

Add `FAILED` to the enum. It is a terminal state -- no transitions out of `FAILED`.

#### 2c. Order Entity Modifications

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/Order.kt`

Add three fields:
- `@Embedded var shippingAddress: ShippingAddress = ShippingAddress()` -- customer shipping address
- `@Column(name = "supplier_order_id") var supplierOrderId: String? = null` -- CJ order ID cross-reference
- `@Column(name = "failure_reason") var failureReason: String? = null` -- categorized failure reason

Update `VALID_TRANSITIONS`:
- `CONFIRMED` adds `FAILED` to its allowed set: `setOf(OrderStatus.SHIPPED, OrderStatus.REFUNDED, OrderStatus.FAILED)`
- `FAILED` is terminal: `OrderStatus.FAILED to emptySet()`

#### 2d. SupplierProductMapping Entity

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/SupplierProductMapping.kt`

```kotlin
@Entity
@Table(name = "supplier_product_mappings")
class SupplierProductMapping(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "supplier", nullable = false)
    val supplier: String,  // "CJ_DROPSHIPPING", "PRINTFUL", etc.

    @Column(name = "supplier_product_id", nullable = false)
    val supplierProductId: String,  // CJ pid

    @Column(name = "supplier_variant_id", nullable = false)
    val supplierVariantId: String,  // CJ vid

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) : Persistable<UUID> {
    @Transient private var isNew: Boolean = true
    override fun getId(): UUID = id
    override fun isNew(): Boolean = isNew
    @PostPersist @PostLoad fun markNotNew() { isNew = false }
}
```

Implements `Persistable<UUID>` per CLAUDE.md constraint #16 (assigned ID). The entity uses `@get:JvmName("_internalId")` is not needed here since we don't have a `getId()` conflict -- the `Persistable` `getId()` method serves both purposes.

### Layer 3: Fulfillment Domain -- Supplier Order Adapter Interface

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/supplier/SupplierOrderAdapter.kt`

```kotlin
interface SupplierOrderAdapter {
    fun supplierName(): String
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderNumber: String,       // internal order UUID as string
    val customerName: String,
    val address: String,
    val city: String,
    val province: String,
    val country: String,
    val countryCode: String,
    val zip: String,
    val phone: String,
    val supplierVariantId: String, // CJ vid
    val quantity: Int
)

sealed class SupplierOrderResult {
    data class Success(val supplierOrderId: String) : SupplierOrderResult()
    data class Failure(val reason: FailureReason, val message: String) : SupplierOrderResult()
}

enum class FailureReason {
    OUT_OF_STOCK,
    INVALID_ADDRESS,
    API_AUTH_FAILURE,
    NETWORK_ERROR,
    UNKNOWN
}
```

`SupplierOrderResult` is a sealed class: forces callers to handle both success and failure. `FailureReason` enum categorizes errors for observability.

### Layer 4: Fulfillment Domain Service -- SupplierOrderPlacementListener

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/SupplierOrderPlacementListener.kt`

```kotlin
@Component
class SupplierOrderPlacementListener(
    private val orderRepository: OrderRepository,
    private val supplierProductMappingRepository: SupplierProductMappingRepository,
    private val supplierOrderAdapters: List<SupplierOrderAdapter>,
    private val meterRegistry: MeterRegistry
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        // 1. Load order
        // 2. Idempotency check: skip if supplierOrderId already set
        // 3. Resolve supplier product mapping
        // 4. Find matching adapter by supplier name
        // 5. Validate shipping address present
        // 6. Build SupplierOrderRequest
        // 7. Call adapter.placeOrder()
        // 8. On success: set supplierOrderId, save
        // 9. On failure: transition to FAILED, set failureReason, save
        // 10. Record Micrometer metrics
    }
}
```

Key behaviors:
- **Idempotency**: If `order.supplierOrderId` is already non-null, logs a warning and returns immediately.
- **Missing mapping**: If no `SupplierProductMapping` exists for the SKU, transitions to `FAILED` with reason `UNKNOWN` and message "No supplier product mapping found for SKU".
- **Missing address**: If shipping address fields are null/blank, transitions to `FAILED` with `INVALID_ADDRESS`.
- **Adapter resolution**: Matches `mapping.supplier` to `adapter.supplierName()`. If no adapter found, transitions to `FAILED`.
- **Metrics**: `supplier.order.placed` (counter, tagged by supplier + outcome), `supplier.order.placement.duration` (timer).

### Layer 5: Fulfillment Domain Service -- OrderService Changes

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

Modify `routeToVendor()` to publish `OrderConfirmed` after saving:

```kotlin
fun routeToVendor(orderId: UUID): Order {
    // ... existing logic ...
    order.updateStatus(OrderStatus.CONFIRMED)
    val saved = orderRepository.save(order)
    eventPublisher.publishEvent(
        OrderConfirmed(orderId = order.orderId(), skuId = order.skuId())
    )
    logger.info("Order {} routed to vendor {}, OrderConfirmed event published", orderId, order.vendorId)
    return saved
}
```

Modify `create()` to accept and persist shipping address (via updated `CreateOrderCommand`).

### Layer 6: Fulfillment Domain -- ChannelOrder Shipping Address

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ChannelOrder.kt`

Add a `ChannelShippingAddress` data class and an optional field on `ChannelOrder`:

```kotlin
data class ChannelShippingAddress(
    val customerName: String?,
    val address1: String?,
    val address2: String?,
    val city: String?,
    val province: String?,
    val country: String?,
    val countryCode: String?,
    val zip: String?,
    val phone: String?
)

data class ChannelOrder(
    // ... existing fields ...
    val shippingAddress: ChannelShippingAddress? = null
)
```

### Layer 7: Fulfillment -- ShopifyOrderAdapter Changes

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/channel/ShopifyOrderAdapter.kt`

Add shipping address extraction from the Shopify webhook JSON using `get()` (not `path()`) per CLAUDE.md constraint #15:

```kotlin
val shippingNode = root.get("shipping_address")
val shippingAddress = if (shippingNode != null && !shippingNode.isNull) {
    ChannelShippingAddress(
        customerName = shippingNode.get("name")?.asText(),
        address1 = shippingNode.get("address1")?.asText(),
        address2 = shippingNode.get("address2")?.asText(),
        city = shippingNode.get("city")?.asText(),
        province = shippingNode.get("province")?.asText(),
        country = shippingNode.get("country")?.asText(),
        countryCode = shippingNode.get("country_code")?.asText(),
        zip = shippingNode.get("zip")?.asText(),
        phone = shippingNode.get("phone")?.asText()
    )
} else null
```

### Layer 8: Fulfillment -- LineItemOrderCreator & CreateOrderCommand Changes

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/CreateOrderCommand.kt`

Add `shippingAddress: ShippingAddress? = null` to `CreateOrderCommand`.

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/LineItemOrderCreator.kt`

Map `ChannelShippingAddress` to `ShippingAddress` domain type and pass through `CreateOrderCommand`. Combine `address1` and `address2` into a single `address` string for the embeddable:

```kotlin
val shippingAddr = channelOrder.shippingAddress?.let { addr ->
    ShippingAddress(
        customerName = addr.customerName,
        address = listOfNotNull(addr.address1, addr.address2).joinToString(", "),
        city = addr.city,
        province = addr.province,
        country = addr.country,
        countryCode = addr.countryCode,
        zip = addr.zip,
        phone = addr.phone
    )
}
```

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/OrderService.kt`

In `create()`, set `order.shippingAddress` from the command if provided.

### Layer 9: Fulfillment Proxy -- CjOrderAdapter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/CjOrderAdapter.kt`

```kotlin
@Component
@Profile("!local")
class CjOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String
) : SupplierOrderAdapter {

    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }

    override fun supplierName(): String = "CJ_DROPSHIPPING"

    @CircuitBreaker(name = "cj-order")
    @Retry(name = "cj-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            return SupplierOrderResult.Failure(FailureReason.API_AUTH_FAILURE, "CJ API credentials not configured")
        }

        val requestBody = mapOf(
            "orderNumber" to request.orderNumber,
            "shippingCustomerName" to request.customerName,
            "shippingAddress" to request.address,
            "shippingCity" to request.city,
            "shippingProvince" to request.province,
            "shippingCountry" to request.country,
            "shippingCountryCode" to request.countryCode,
            "shippingZip" to request.zip,
            "shippingPhone" to request.phone,
            "fromCountryCode" to "CN",
            "logisticName" to "CJPacket Ordinary",
            "products" to listOf(
                mapOf(
                    "vid" to request.supplierVariantId,
                    "quantity" to request.quantity
                )
            )
        )

        val response = restClient.post()
            .uri("/api2.0/v1/shopping/order/createOrderV2")
            .header("CJ-Access-Token", accessToken)
            .header("Content-Type", "application/json")
            .body(requestBody)
            .retrieve()
            .body(JsonNode::class.java)

        // CJ returns HTTP 200 for ALL responses -- check JSON code field
        val code = response?.get("code")?.asInt() ?: -1
        if (code == 200) {
            val orderId = response?.get("data")?.get("orderId")?.asText()
                ?: return SupplierOrderResult.Failure(FailureReason.UNKNOWN, "CJ returned success but missing orderId")
            return SupplierOrderResult.Success(supplierOrderId = orderId)
        }

        // Map CJ error codes to FailureReason
        val message = response?.get("message")?.asText() ?: "Unknown CJ API error"
        val reason = when (code) {
            1600001, 1600100 -> FailureReason.API_AUTH_FAILURE
            1600200 -> FailureReason.NETWORK_ERROR  // rate limited, transient
            else -> when {
                message.contains("stock", ignoreCase = true) -> FailureReason.OUT_OF_STOCK
                message.contains("address", ignoreCase = true) -> FailureReason.INVALID_ADDRESS
                else -> FailureReason.UNKNOWN
            }
        }
        return SupplierOrderResult.Failure(reason, message)
    }
}
```

Key design:
- Uses `get()` not `path()` for JSON fields where null-coalescing is needed (CLAUDE.md #15).
- `@Value` annotations use empty defaults `${key:}` (CLAUDE.md #13).
- `@CircuitBreaker` + `@Retry` consistent with `StripeRefundAdapter`, `ShopifyInventoryCheckAdapter`.
- Request body is JSON (not form-encoded), so no URL-encoding needed -- serialized via RestClient's Jackson message converter.
- `restClient` is lazy-initialized to avoid issues with blank `baseUrl` at construction time.

### Layer 10: Fulfillment Proxy -- Stub Supplier Order Adapter

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/proxy/supplier/StubSupplierOrderConfiguration.kt`

```kotlin
@Configuration
@Profile("local")
class StubSupplierOrderConfiguration {
    @Bean
    fun stubSupplierOrderAdapter(): SupplierOrderAdapter = object : SupplierOrderAdapter {
        override fun supplierName(): String = "CJ_DROPSHIPPING"
        override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult =
            SupplierOrderResult.Success(supplierOrderId = "stub_cj_${UUID.randomUUID()}")
    }
}
```

Follows the `StubRefundConfiguration` and `StubInventoryConfiguration` pattern.

### Layer 11: Persistence -- SupplierProductMappingRepository

**File:** `modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/persistence/SupplierProductMappingRepository.kt`

```kotlin
@Repository
interface SupplierProductMappingRepository : JpaRepository<SupplierProductMapping, UUID> {
    fun findBySkuId(skuId: UUID): SupplierProductMapping?
    fun findBySkuIdAndSupplier(skuId: UUID, supplier: String): SupplierProductMapping?
}
```

### Layer 12: Database Migration -- V21

**File:** `modules/app/src/main/resources/db/migration/V21__supplier_order_placement.sql`

```sql
-- Shipping address columns on orders table
ALTER TABLE orders ADD COLUMN shipping_customer_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_address       VARCHAR(500);
ALTER TABLE orders ADD COLUMN shipping_city           VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_province       VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country        VARCHAR(255);
ALTER TABLE orders ADD COLUMN shipping_country_code   VARCHAR(10);
ALTER TABLE orders ADD COLUMN shipping_zip            VARCHAR(50);
ALTER TABLE orders ADD COLUMN shipping_phone          VARCHAR(50);

-- Supplier order cross-reference
ALTER TABLE orders ADD COLUMN supplier_order_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN failure_reason    VARCHAR(255);

-- Supplier product mappings table
CREATE TABLE supplier_product_mappings (
    id                  UUID PRIMARY KEY,
    sku_id              UUID         NOT NULL REFERENCES skus(id),
    supplier            VARCHAR(50)  NOT NULL,
    supplier_product_id VARCHAR(255) NOT NULL,
    supplier_variant_id VARCHAR(255) NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_supplier_product_mappings_sku_supplier
    ON supplier_product_mappings(sku_id, supplier);

CREATE INDEX idx_supplier_product_mappings_sku_id
    ON supplier_product_mappings(sku_id);
```

All new columns on `orders` are nullable -- existing rows do not need backfill. The unique index on `(sku_id, supplier)` enforces one mapping per SKU per supplier.

## Task Breakdown

### Shared Module
- [x] Add `OrderConfirmed` domain event (`modules/shared/src/main/kotlin/com/autoshipper/shared/events/OrderConfirmed.kt`)

### Database Migration
- [x] Write `V21__supplier_order_placement.sql` with shipping address columns, supplier_order_id, failure_reason, supplier_product_mappings table

### Fulfillment Domain -- Value Types & Entities
- [x] Create `ShippingAddress` embeddable (`modules/fulfillment/.../domain/ShippingAddress.kt`)
- [x] Add `FAILED` to `OrderStatus` enum
- [x] Update `Order.VALID_TRANSITIONS` to include `CONFIRMED -> FAILED` and `FAILED -> emptySet()`
- [x] Add `shippingAddress: ShippingAddress`, `supplierOrderId: String?`, `failureReason: String?` to `Order` entity
- [x] Create `SupplierProductMapping` entity with `Persistable<UUID>` (CLAUDE.md #16)

### Fulfillment Domain -- Supplier Order Interface
- [x] Create `SupplierOrderAdapter` interface, `SupplierOrderRequest`, `SupplierOrderResult` sealed class, `FailureReason` enum (`modules/fulfillment/.../domain/supplier/`)

### Fulfillment Domain -- Channel Order Changes
- [x] Add `ChannelShippingAddress` data class to `ChannelOrder.kt`
- [x] Add optional `shippingAddress: ChannelShippingAddress?` field to `ChannelOrder`

### Fulfillment Domain -- Shopify Adapter Changes
- [x] Extract `shipping_address` from Shopify webhook JSON in `ShopifyOrderAdapter.parse()` using `get()` (CLAUDE.md #15)

### Fulfillment Domain -- Order Creation Flow Changes
- [x] Add `shippingAddress: ShippingAddress?` to `CreateOrderCommand`
- [x] Update `LineItemOrderCreator.processLineItem()` to map `ChannelShippingAddress` to `ShippingAddress` and pass via `CreateOrderCommand`
- [x] Update `OrderService.create()` to set `shippingAddress` on the `Order` entity from `CreateOrderCommand`

### Fulfillment Domain -- Event Publishing
- [x] Update `OrderService.routeToVendor()` to publish `OrderConfirmed` event after save

### Fulfillment Domain Service -- Supplier Order Listener
- [x] Implement `SupplierOrderPlacementListener` with `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` (CLAUDE.md #6)
- [x] Implement idempotency check (skip if `supplierOrderId` already set)
- [x] Implement supplier product mapping resolution
- [x] Implement adapter resolution by supplier name
- [x] Implement shipping address validation
- [x] Implement success path: store `supplierOrderId` on order
- [x] Implement failure path: transition to `FAILED`, store categorized `failureReason`
- [x] Add Micrometer metrics for placement attempts, successes, failures

### Fulfillment Proxy -- CJ Order Adapter
- [x] Implement `CjOrderAdapter` (`@Profile("!local")`, `@CircuitBreaker`, `@Retry`, `CJ-Access-Token` header, `@Value` with empty defaults)
- [x] Implement CJ error code parsing (HTTP 200 for all responses, inspect JSON `code` field)
- [x] Map CJ error codes to `FailureReason` enum values

### Fulfillment Proxy -- Stub Adapter
- [x] Implement `StubSupplierOrderConfiguration` (`@Profile("local")`)

### Fulfillment Persistence
- [x] Create `SupplierProductMappingRepository` interface

### Testing
- [x] WireMock contract test: `CjOrderAdapter` happy path -- order placed, CJ order ID returned
- [x] WireMock contract test: CJ returns error code (out of stock) -- `Failure` result with `OUT_OF_STOCK`
- [x] WireMock contract test: CJ returns auth error (1600001) -- `Failure` result with `API_AUTH_FAILURE`
- [x] WireMock contract test: CJ returns rate limit (1600200) -- `Failure` result with `NETWORK_ERROR`
- [x] WireMock contract test: Blank credentials -- returns `Failure` without calling API
- [x] Unit test: `SupplierOrderPlacementListener` happy path -- adapter called, supplierOrderId stored
- [x] Unit test: `SupplierOrderPlacementListener` idempotency -- supplierOrderId already set, adapter NOT called
- [x] Unit test: `SupplierOrderPlacementListener` no mapping -- order transitions to FAILED
- [x] Unit test: `SupplierOrderPlacementListener` missing shipping address -- order transitions to FAILED
- [x] Unit test: `SupplierOrderPlacementListener` adapter failure -- order transitions to FAILED with reason
- [x] Unit test: `ShopifyOrderAdapter` parses shipping_address from webhook JSON
- [x] Unit test: `ShopifyOrderAdapter` handles missing shipping_address gracefully
- [x] Unit test: `OrderStatus.FAILED` valid transitions (`CONFIRMED -> FAILED`, no transitions out of `FAILED`)
- [x] Integration test: full event chain -- routeToVendor publishes OrderConfirmed, listener places order, supplierOrderId stored
- [x] Integration test: failure path -- CJ rejects, order marked FAILED with failure reason persisted (verifies REQUIRES_NEW tx commits failure state)
- [x] WireMock fixture: `cj/create-order-success.json` based on CJ API docs
- [x] WireMock fixture: `cj/create-order-out-of-stock.json` based on CJ API docs
- [x] WireMock fixture: `cj/create-order-auth-error.json` based on CJ API docs

## Testing Strategy

### WireMock Contract Tests

Create a `CjOrderAdapterWireMockTest` class extending a test base (similar to `CjDropshippingAdapterWireMockTest` in the portfolio module). WireMock fixtures must be based on real CJ API documentation (per feedback in `feedback_wiremock_fixtures.md`), not reverse-engineered from adapter code.

CJ API `createOrderV2` response format (from docs):
```json
{
  "code": 200,
  "result": true,
  "message": "success",
  "data": {
    "orderId": "2103221234567890",
    "orderNum": "CJ-2103221234567890"
  }
}
```

Fixtures needed:
- `wiremock/cj/create-order-success.json` -- code 200, data with orderId
- `wiremock/cj/create-order-out-of-stock.json` -- code != 200, stock-related message
- `wiremock/cj/create-order-auth-error.json` -- code 1600001

Test fixtures go in `modules/fulfillment/src/test/resources/wiremock/cj/`.

A `WireMockAdapterTestBase` for the fulfillment module (or reuse from portfolio -- evaluate at implementation time whether to extract to shared test utils or duplicate for module independence).

### Unit Tests

- `SupplierOrderPlacementListenerTest`: Mock `OrderRepository`, `SupplierProductMappingRepository`, `SupplierOrderAdapter`. Test all paths: happy path, idempotency skip, missing mapping, missing address, adapter failure. Verify exact `failureReason` values stored (not `any()` matchers -- per testing conventions).
- `ShopifyOrderAdapterTest`: Extend existing tests to verify shipping address extraction. Include test for missing `shipping_address` node (should produce `null`, not throw).
- `OrderTransitionTest`: Verify `CONFIRMED -> FAILED` is valid, `FAILED -> *` throws.

### Integration Tests

- **Full event chain test**: Uses `TransactionTemplate.execute {}` to publish `OrderConfirmed` inside a real transaction (per testing conventions for `@TransactionalEventListener` tests). Verify that after the event processes, the order has `supplierOrderId` set.
- **Failure persistence test**: Verify that when the adapter returns `Failure`, the order's `FAILED` status and `failureReason` are persisted (not rolled back). This catches the REQUIRES_NEW transaction boundary bug described in CLAUDE.md #6.

### Shopify Webhook Test

Extend existing `ShopifyOrderAdapter` tests (if any) or add new ones to verify the `shipping_address` extraction with a realistic Shopify webhook payload.

## Rollout Plan

### Dependency Order

Implementation must proceed in this order due to compile-time dependencies:

1. **V21 migration** -- schema must exist before entities reference new columns
2. **Shared module**: `OrderConfirmed` event -- needed by both publisher and listener
3. **Fulfillment domain types**: `ShippingAddress`, `OrderStatus.FAILED`, `Order` entity changes, `SupplierProductMapping` entity
4. **Fulfillment domain interfaces**: `SupplierOrderAdapter`, `SupplierOrderRequest`, `SupplierOrderResult`
5. **Fulfillment persistence**: `SupplierProductMappingRepository`
6. **Channel order changes**: `ChannelShippingAddress`, `ShopifyOrderAdapter` update, `LineItemOrderCreator` update, `CreateOrderCommand` update, `OrderService.create()` update
7. **Event publishing**: `OrderService.routeToVendor()` publishes `OrderConfirmed`
8. **Supplier order listener**: `SupplierOrderPlacementListener`
9. **CJ adapter**: `CjOrderAdapter` + `StubSupplierOrderConfiguration`
10. **Tests**: WireMock fixtures and contract tests, unit tests, integration tests

### Configuration

Add to `application.yml` (or environment variables):

```yaml
cj-dropshipping:
  api:
    base-url: https://developers.cjdropshipping.com
    access-token: ${CJ_ACCESS_TOKEN:}
```

No new dependencies in `build.gradle.kts` -- the fulfillment module already has `resilience4j`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, and `jackson-module-kotlin`. WireMock test dependency should be added:

```kotlin
testImplementation("org.wiremock:wiremock-standalone:3.5.4")
```

### Resilience4j Configuration

Add to `application.yml`:

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
        wait-duration: 2s
```

### Risks and Mitigations

| Risk | Mitigation |
|---|---|
| CJ `vid` not available at order time (mapping not populated) | Order transitions to FAILED with clear reason; mapping population is a prerequisite from demand scan pipeline |
| CJ API returns HTTP 200 for errors | Adapter inspects JSON `code` field, not HTTP status -- same pattern as portfolio `CjDropshippingAdapter` |
| Duplicate supplier orders from event redelivery | Two-layer idempotency: check `supplierOrderId` before calling API + use `order.id` as `orderNumber` |
| Shipping address missing from webhook | Order creates successfully (address is nullable), but supplier placement listener transitions to FAILED with `INVALID_ADDRESS` |
| REQUIRES_NEW transaction not committing failure state | Integration test explicitly verifies FAILED status is persisted after adapter failure |
