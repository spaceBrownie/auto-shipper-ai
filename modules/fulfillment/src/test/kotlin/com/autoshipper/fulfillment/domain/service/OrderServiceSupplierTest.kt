package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Tests for OrderService.markFailed() and OrderService.setSupplierOrderId() — new methods
 * added for FR-025 supplier order placement support.
 *
 * These tests will FAIL until the methods are implemented in Phase 5.
 */
@ExtendWith(MockitoExtension::class)
class OrderServiceSupplierTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var orderService: OrderService

    private fun confirmedOrder(): Order {
        val order = Order(
            idempotencyKey = "test-idem-${UUID.randomUUID()}",
            skuId = UUID.randomUUID(),
            vendorId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            totalAmount = BigDecimal("59.98"),
            totalCurrency = Currency.USD,
            quantity = 1,
            paymentIntentId = "pi_test_123",
            status = OrderStatus.PENDING
        )
        order.updateStatus(OrderStatus.CONFIRMED)
        return order
    }

    // --- BR-5: markFailed ---

    @Test
    fun `markFailed transitions CONFIRMED order to FAILED with reason`() {
        val order = confirmedOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.markFailed(order.id, "CJ API error: product unavailable")
        assert(result.status == OrderStatus.FAILED) {
            "Expected FAILED status but got ${result.status}"
        }
        assert(result.failureReason == "CJ API error: product unavailable") {
            "Expected failure reason 'CJ API error: product unavailable' but got '${result.failureReason}'"
        }
    }

    @Test
    fun `markFailed throws for non-existent order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        assertThrows<IllegalArgumentException> { orderService.markFailed(unknownId, "reason") }
    }

    // --- BR-4: setSupplierOrderId ---

    @Test
    fun `setSupplierOrderId stores CJ order ID on existing order`() {
        val order = confirmedOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.setSupplierOrderId(order.id, "CJ-ORD-12345")
        assert(result.supplierOrderId == "CJ-ORD-12345") {
            "Expected supplierOrderId 'CJ-ORD-12345' but got '${result.supplierOrderId}'"
        }
    }

    @Test
    fun `setSupplierOrderId throws for non-existent order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        assertThrows<IllegalArgumentException> { orderService.setSupplierOrderId(unknownId, "CJ-ORD-12345") }
    }
}
