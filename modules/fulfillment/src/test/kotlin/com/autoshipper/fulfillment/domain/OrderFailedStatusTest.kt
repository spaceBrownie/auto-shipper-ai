package com.autoshipper.fulfillment.domain

import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * Tests for the FAILED OrderStatus — a new terminal state for orders that fail
 * during supplier order placement.
 *
 * These tests will FAIL until:
 * 1. OrderStatus.FAILED is added to the enum
 * 2. Order.VALID_TRANSITIONS includes CONFIRMED -> FAILED
 * 3. Order.VALID_TRANSITIONS includes FAILED -> emptySet() (terminal)
 */
class OrderFailedStatusTest {

    private fun pendingOrder(): Order = Order(
        idempotencyKey = "test-idem-${UUID.randomUUID()}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.99"),
        totalCurrency = Currency.USD,
        quantity = 1,
        paymentIntentId = "pi_test_123",
        status = OrderStatus.PENDING
    )

    // --- BR-5: FAILED is a valid transition from CONFIRMED ---

    @Test
    fun `CONFIRMED order can transition to FAILED`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)

        // This will FAIL until FAILED is added to VALID_TRANSITIONS[CONFIRMED]
        order.updateStatus(OrderStatus.FAILED)

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED but got ${order.status}"
        }
    }

    // --- BR-5: FAILED is terminal ---

    @Test
    fun `FAILED order cannot transition to CONFIRMED`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.CONFIRMED)
        }
    }

    @Test
    fun `FAILED order cannot transition to SHIPPED`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.SHIPPED)
        }
    }

    @Test
    fun `FAILED order cannot transition to PENDING`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.PENDING)
        }
    }

    @Test
    fun `FAILED order cannot transition to REFUNDED`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.FAILED)

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.REFUNDED)
        }
    }

    // --- Boundary: PENDING cannot transition directly to FAILED ---

    @Test
    fun `PENDING order cannot transition directly to FAILED`() {
        val order = pendingOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.FAILED)
        }
    }

    // --- Boundary: SHIPPED cannot transition to FAILED ---

    @Test
    fun `SHIPPED order cannot transition to FAILED`() {
        val order = pendingOrder()
        order.updateStatus(OrderStatus.CONFIRMED)
        order.updateStatus(OrderStatus.SHIPPED)

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.FAILED)
        }
    }
}
