package com.autoshipper.fulfillment.domain

import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

class OrderFailedTransitionTest {

    private fun pendingOrder(): Order = Order(
        idempotencyKey = "test-${UUID.randomUUID()}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.99"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_${UUID.randomUUID()}",
        status = OrderStatus.PENDING
    )

    private fun confirmedOrder(): Order = pendingOrder().apply {
        updateStatus(OrderStatus.CONFIRMED)
    }

    private fun shippedOrder(): Order = confirmedOrder().apply {
        updateStatus(OrderStatus.SHIPPED)
    }

    private fun deliveredOrder(): Order = shippedOrder().apply {
        updateStatus(OrderStatus.DELIVERED)
    }

    private fun refundedOrder(): Order = pendingOrder().apply {
        updateStatus(OrderStatus.REFUNDED)
    }

    private fun failedOrder(): Order = pendingOrder().apply {
        updateStatus(OrderStatus.FAILED)
    }

    @Test
    fun `confirmed order can transition to FAILED`() {
        val order = confirmedOrder()

        order.updateStatus(OrderStatus.FAILED)

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED but got ${order.status}"
        }
    }

    @Test
    fun `pending order can transition to FAILED`() {
        val order = pendingOrder()

        order.updateStatus(OrderStatus.FAILED)

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED but got ${order.status}"
        }
    }

    @Test
    fun `FAILED is terminal — cannot transition to CONFIRMED`() {
        val order = failedOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.CONFIRMED)
        }
    }

    @Test
    fun `FAILED is terminal — cannot transition to SHIPPED`() {
        val order = failedOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.SHIPPED)
        }
    }

    @Test
    fun `FAILED is terminal — cannot transition to PENDING`() {
        val order = failedOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.PENDING)
        }
    }

    @Test
    fun `SHIPPED order cannot transition to FAILED`() {
        val order = shippedOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.FAILED)
        }
    }

    @Test
    fun `DELIVERED order cannot transition to FAILED`() {
        val order = deliveredOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.FAILED)
        }
    }

    @Test
    fun `REFUNDED order cannot transition to FAILED`() {
        val order = refundedOrder()

        assertThrows<IllegalArgumentException> {
            order.updateStatus(OrderStatus.FAILED)
        }
    }
}
