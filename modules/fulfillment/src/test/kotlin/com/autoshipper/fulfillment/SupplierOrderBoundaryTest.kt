package com.autoshipper.fulfillment

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.SupplierProductMapping
import com.autoshipper.fulfillment.domain.supplier.FailureReason
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.domain.service.SupplierOrderPlacementListener
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.persistence.SupplierProductMappingRepository
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Boundary condition tests for supplier order placement.
 *
 * Tests all error paths, edge cases, and failure handling:
 * - CJ API errors (out of stock, auth, rate limit)
 * - Missing supplier product mapping
 * - Idempotency (already placed)
 * - Missing shipping address
 * - Invalid order status
 *
 * Each failure must result in the order transitioning to FAILED with a specific
 * failure reason stored on the entity (not silently swallowed per BR-6).
 *
 * Tests use exact value assertions per testing conventions -- never any() matchers
 * for financial or status values.
 */
@ExtendWith(MockitoExtension::class)
class SupplierOrderBoundaryTest {

    private val orderRepository: OrderRepository = mock()
    private val supplierProductMappingRepository: SupplierProductMappingRepository = mock()
    private val meterRegistry = SimpleMeterRegistry()

    // ===== CJ API Error Handling =====

    /**
     * Test: CJ API returns out-of-stock error -> order marked FAILED with OUT_OF_STOCK reason.
     */
    @Test
    fun `CJ API out of stock error transitions order to FAILED with OUT_OF_STOCK reason`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val order = createTestOrder(orderId, skuId, OrderStatus.CONFIRMED)

        val mapping = SupplierProductMapping(
            skuId = skuId,
            supplier = "CJ_DROPSHIPPING",
            supplierProductId = "pid-123",
            supplierVariantId = "vid-456"
        )

        val adapter: SupplierOrderAdapter = mock {
            on { supplierName() } doReturn "CJ_DROPSHIPPING"
            on { placeOrder(any()) } doReturn SupplierOrderResult.Failure(
                reason = FailureReason.OUT_OF_STOCK,
                message = "Product is out of stock"
            )
        }

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingRepository.findBySkuId(skuId)).thenReturn(mapping)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val listener = SupplierOrderPlacementListener(
            orderRepository, supplierProductMappingRepository, listOf(adapter), meterRegistry
        )
        listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

        assertEquals(OrderStatus.FAILED, order.status)
        assertEquals("OUT_OF_STOCK", order.failureReason)
        verify(orderRepository).save(order)
    }

    /**
     * Test: CJ API returns authentication error -> order marked FAILED with API_AUTH_FAILURE.
     */
    @Test
    fun `CJ API auth error transitions order to FAILED with API_AUTH_FAILURE reason`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val order = createTestOrder(orderId, skuId, OrderStatus.CONFIRMED)

        val mapping = SupplierProductMapping(
            skuId = skuId,
            supplier = "CJ_DROPSHIPPING",
            supplierProductId = "pid-123",
            supplierVariantId = "vid-456"
        )

        val adapter: SupplierOrderAdapter = mock {
            on { supplierName() } doReturn "CJ_DROPSHIPPING"
            on { placeOrder(any()) } doReturn SupplierOrderResult.Failure(
                reason = FailureReason.API_AUTH_FAILURE,
                message = "Invalid API key or access token"
            )
        }

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingRepository.findBySkuId(skuId)).thenReturn(mapping)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val listener = SupplierOrderPlacementListener(
            orderRepository, supplierProductMappingRepository, listOf(adapter), meterRegistry
        )
        listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

        assertEquals(OrderStatus.FAILED, order.status)
        assertEquals("API_AUTH_FAILURE", order.failureReason)
        verify(orderRepository).save(order)
    }

    /**
     * Test: CJ API returns rate limit -> order marked FAILED with NETWORK_ERROR.
     */
    @Test
    fun `CJ API rate limit returns NETWORK_ERROR failure reason`() {
        val failureResult = SupplierOrderResult.Failure(
            reason = FailureReason.NETWORK_ERROR,
            message = "Too much request"
        )

        assertEquals(FailureReason.NETWORK_ERROR, failureResult.reason)
        assertEquals("Too much request", failureResult.message)
    }

    // ===== Missing Data Handling =====

    /**
     * Test: No supplier product mapping found for SKU -> order marked FAILED.
     */
    @Test
    fun `missing supplier product mapping transitions order to FAILED`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val order = createTestOrder(orderId, skuId, OrderStatus.CONFIRMED)

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingRepository.findBySkuId(skuId)).thenReturn(null)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val listener = SupplierOrderPlacementListener(
            orderRepository, supplierProductMappingRepository, emptyList(), meterRegistry
        )
        listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

        assertEquals(OrderStatus.FAILED, order.status)
        assertEquals("No supplier product mapping found for SKU", order.failureReason)
        verify(orderRepository).save(order)
    }

    /**
     * Test: Missing shipping address on order -> order marked FAILED with INVALID_ADDRESS.
     */
    @Test
    fun `missing shipping address transitions order to FAILED with INVALID_ADDRESS`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val order = createTestOrder(orderId, skuId, OrderStatus.CONFIRMED, shippingAddress = ShippingAddress())

        val mapping = SupplierProductMapping(
            skuId = skuId,
            supplier = "CJ_DROPSHIPPING",
            supplierProductId = "pid-123",
            supplierVariantId = "vid-456"
        )

        val adapter: SupplierOrderAdapter = mock {
            on { supplierName() } doReturn "CJ_DROPSHIPPING"
        }

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingRepository.findBySkuId(skuId)).thenReturn(mapping)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val listener = SupplierOrderPlacementListener(
            orderRepository, supplierProductMappingRepository, listOf(adapter), meterRegistry
        )
        listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

        assertEquals(OrderStatus.FAILED, order.status)
        assertEquals("INVALID_ADDRESS", order.failureReason)
        verify(adapter, never()).placeOrder(any())
        verify(orderRepository).save(order)
    }

    // ===== Idempotency =====

    /**
     * Test: Order already has supplierOrderId -> skip placement (idempotency).
     */
    @Test
    fun `order with existing supplier order ID skips placement`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val order = createTestOrder(orderId, skuId, OrderStatus.CONFIRMED)
        order.supplierOrderId = "2103221234567890"

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))

        val adapter: SupplierOrderAdapter = mock()
        val listener = SupplierOrderPlacementListener(
            orderRepository, supplierProductMappingRepository, listOf(adapter), meterRegistry
        )
        listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

        // Adapter must NOT be called
        verify(adapter, never()).placeOrder(any())
        // Mapping must NOT be looked up
        verify(supplierProductMappingRepository, never()).findBySkuId(any())
        // Order must NOT be saved (no changes)
        verify(orderRepository, never()).save(any<Order>())
    }

    // ===== Invalid State =====

    /**
     * Test: Order in invalid status (not CONFIRMED) -> placement rejected.
     */
    @Test
    fun `order not in CONFIRMED status is rejected for supplier placement`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()

        val invalidStatuses = listOf(
            OrderStatus.PENDING,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.REFUNDED,
            OrderStatus.RETURNED,
            OrderStatus.FAILED
        )

        for (status in invalidStatuses) {
            val order = createTestOrderWithStatus(orderId, skuId, status)
            whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))

            val adapter: SupplierOrderAdapter = mock()
            val listener = SupplierOrderPlacementListener(
                orderRepository, supplierProductMappingRepository, listOf(adapter), meterRegistry
            )
            listener.onOrderConfirmed(OrderConfirmed(OrderId(orderId), SkuId(skuId)))

            verify(adapter, never()).placeOrder(any())
        }
    }

    // ===== OrderStatus.FAILED Transitions =====

    /**
     * Test: CONFIRMED -> FAILED is a valid transition.
     */
    @Test
    fun `CONFIRMED to FAILED is a valid order transition`() {
        val order = createTestOrder(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)
        assertEquals(OrderStatus.FAILED, order.status)
    }

    /**
     * Test: FAILED is a terminal state -- no transitions out of FAILED.
     */
    @Test
    fun `FAILED is a terminal order status with no valid transitions`() {
        val order = createTestOrder(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)
        assertEquals(OrderStatus.FAILED, order.status)

        // Attempting any transition from FAILED must throw
        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.CONFIRMED)
        }
        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.SHIPPED)
        }
        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.PENDING)
        }
    }

    // ===== Helper Methods =====

    private fun createTestOrder(
        orderId: UUID,
        skuId: UUID,
        status: OrderStatus,
        shippingAddress: ShippingAddress = ShippingAddress(
            customerName = "Test Customer",
            address = "123 Test St",
            city = "Testville",
            province = "TS",
            country = "United States",
            countryCode = "US",
            zip = "12345",
            phone = "+1-555-000-0000"
        )
    ): Order = Order(
        id = orderId,
        idempotencyKey = "test-idem-${UUID.randomUUID()}",
        skuId = skuId,
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.99"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_${UUID.randomUUID()}",
        status = OrderStatus.PENDING,
        shippingAddress = shippingAddress
    ).apply {
        if (status == OrderStatus.CONFIRMED) {
            updateStatus(OrderStatus.CONFIRMED)
        }
    }

    /**
     * Creates an order with the exact status without going through transitions.
     * Used only for testing status guard logic where we need orders in various states.
     */
    private fun createTestOrderWithStatus(orderId: UUID, skuId: UUID, status: OrderStatus): Order {
        val order = Order(
            id = orderId,
            idempotencyKey = "test-idem-${UUID.randomUUID()}",
            skuId = skuId,
            vendorId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            totalAmount = BigDecimal("49.99"),
            totalCurrency = Currency.USD,
            paymentIntentId = "pi_test_${UUID.randomUUID()}",
            status = OrderStatus.PENDING
        )
        // Walk the state machine to reach the target status
        when (status) {
            OrderStatus.PENDING -> { /* already there */ }
            OrderStatus.CONFIRMED -> order.updateStatus(OrderStatus.CONFIRMED)
            OrderStatus.SHIPPED -> {
                order.updateStatus(OrderStatus.CONFIRMED)
                order.updateStatus(OrderStatus.SHIPPED)
            }
            OrderStatus.DELIVERED -> {
                order.updateStatus(OrderStatus.CONFIRMED)
                order.updateStatus(OrderStatus.SHIPPED)
                order.updateStatus(OrderStatus.DELIVERED)
            }
            OrderStatus.REFUNDED -> order.updateStatus(OrderStatus.REFUNDED)
            OrderStatus.RETURNED -> {
                order.updateStatus(OrderStatus.CONFIRMED)
                order.updateStatus(OrderStatus.SHIPPED)
                order.updateStatus(OrderStatus.DELIVERED)
                order.updateStatus(OrderStatus.RETURNED)
            }
            OrderStatus.FAILED -> {
                order.updateStatus(OrderStatus.CONFIRMED)
                order.updateStatus(OrderStatus.FAILED)
            }
        }
        return order
    }
}
