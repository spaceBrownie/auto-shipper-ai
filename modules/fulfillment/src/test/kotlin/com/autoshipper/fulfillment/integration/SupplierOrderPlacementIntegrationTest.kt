package com.autoshipper.fulfillment.integration

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.domain.service.SupplierOrderPlacementService
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.proxy.supplier.SupplierProductMapping
import com.autoshipper.fulfillment.proxy.supplier.SupplierProductMappingResolver
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Unit-level integration test for the supplier order placement chain.
 * Tests the logical flow: Order created -> routeToVendor -> OrderConfirmed event ->
 * SupplierOrderPlacementService -> SupplierOrderAdapter, all wired with mocks.
 */
@ExtendWith(MockitoExtension::class)
class SupplierOrderPlacementIntegrationTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var supplierOrderAdapter: SupplierOrderAdapter

    @Mock
    lateinit var supplierProductMappingResolver: SupplierProductMappingResolver

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    /**
     * In-memory store simulating repository persistence so that
     * mutations made by OrderService and SupplierOrderPlacementService
     * are visible across the chain.
     */
    private fun setupInMemoryStore(): MutableMap<UUID, Order> {
        val store = mutableMapOf<UUID, Order>()

        whenever(orderRepository.save(any<Order>())).thenAnswer { invocation ->
            val order = invocation.arguments[0] as Order
            store[order.id] = order
            order
        }

        whenever(orderRepository.findById(any<UUID>())).thenAnswer { invocation ->
            val id = invocation.arguments[0] as UUID
            Optional.ofNullable(store[id])
        }

        return store
    }

    @Test
    fun `full chain - order created, routed, supplier order placed successfully stores supplierOrderId`() {
        val store = setupInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create order
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("79.99"), Currency.USD),
            paymentIntentId = "pi_integration_1",
            idempotencyKey = "integration-supplier-test-1",
            quantity = 2,
            shippingAddress = ShippingAddress(
                customerName = "Integration Test User",
                addressLine1 = "100 Test Blvd",
                city = "Testville",
                province = "TX",
                countryCode = "US",
                country = "United States",
                zip = "75001"
            )
        )
        val (order, created) = orderService.create(command)
        assertThat(created).isTrue()
        assertThat(order.status).isEqualTo(OrderStatus.PENDING)

        // Step 2: Route to vendor (transitions to CONFIRMED, publishes OrderConfirmed)
        val confirmedOrder = orderService.routeToVendor(order.id)
        assertThat(confirmedOrder.status).isEqualTo(OrderStatus.CONFIRMED)

        // Capture the published OrderConfirmed event
        val eventCaptor = argumentCaptor<OrderConfirmed>()
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        val orderConfirmedEvent = eventCaptor.firstValue
        assertThat(orderConfirmedEvent.orderId.value).isEqualTo(order.id)

        // Step 3: Simulate event listener calling SupplierOrderPlacementService
        val mapping = SupplierProductMapping(
            supplierProductId = "cj-pid-integration",
            supplierVariantId = "cj-vid-integration"
        )
        whenever(supplierProductMappingResolver.resolve(skuId)).thenReturn(mapping)
        whenever(supplierOrderAdapter.placeOrder(any())).thenReturn(
            SupplierOrderResult.Success("cj-supplier-order-99")
        )

        val placementService = SupplierOrderPlacementService(
            orderRepository, supplierOrderAdapter, supplierProductMappingResolver
        )
        placementService.placeSupplierOrder(orderConfirmedEvent.orderId.value)

        // Verify: supplierOrderId stored on the order
        val finalOrder = store[order.id]!!
        assertThat(finalOrder.supplierOrderId).isEqualTo("cj-supplier-order-99")
        assertThat(finalOrder.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(finalOrder.failureReason).isNull()

        // Verify adapter was called with correct request shape
        verify(supplierOrderAdapter).placeOrder(argThat<SupplierOrderRequest> {
            orderNumber == order.id.toString() &&
                quantity == 2 &&
                supplierVariantId == "cj-vid-integration" &&
                supplierProductId == "cj-pid-integration" &&
                shippingAddress?.customerName == "Integration Test User" &&
                shippingAddress?.city == "Testville"
        })
    }

    @Test
    fun `full chain - CJ rejects order, order marked FAILED with failureReason`() {
        val store = setupInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create order
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("49.99"), Currency.USD),
            paymentIntentId = "pi_integration_fail",
            idempotencyKey = "integration-supplier-fail-1",
            quantity = 1,
            shippingAddress = ShippingAddress(
                customerName = "Fail Test User",
                addressLine1 = "999 Error Lane",
                city = "Failtown",
                province = "CA",
                countryCode = "US",
                country = "United States",
                zip = "90000"
            )
        )
        val (order, _) = orderService.create(command)

        // Step 2: Route to vendor
        orderService.routeToVendor(order.id)

        // Step 3: Supplier order placement fails
        val mapping = SupplierProductMapping(
            supplierProductId = "cj-pid-fail",
            supplierVariantId = "cj-vid-fail"
        )
        whenever(supplierProductMappingResolver.resolve(skuId)).thenReturn(mapping)
        whenever(supplierOrderAdapter.placeOrder(any())).thenReturn(
            SupplierOrderResult.Failure("product out of stock")
        )

        val placementService = SupplierOrderPlacementService(
            orderRepository, supplierOrderAdapter, supplierProductMappingResolver
        )
        placementService.placeSupplierOrder(order.id)

        // Verify: order marked FAILED with reason, no supplierOrderId
        val finalOrder = store[order.id]!!
        assertThat(finalOrder.status).isEqualTo(OrderStatus.FAILED)
        assertThat(finalOrder.failureReason).isEqualTo("product out of stock")
        assertThat(finalOrder.supplierOrderId).isNull()
    }
}
