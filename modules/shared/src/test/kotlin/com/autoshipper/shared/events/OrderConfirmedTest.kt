package com.autoshipper.shared.events

import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Tests for OrderConfirmed domain event — verifies it implements DomainEvent
 * and carries the required fields (orderId, skuId, occurredAt).
 */
class OrderConfirmedTest {

    @Test
    fun `OrderConfirmed implements DomainEvent`() {
        val event = OrderConfirmed(
            orderId = OrderId(UUID.randomUUID()),
            skuId = SkuId(UUID.randomUUID())
        )

        assert(event is DomainEvent) {
            "OrderConfirmed must implement DomainEvent"
        }
    }

    @Test
    fun `OrderConfirmed carries orderId and skuId`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()

        val event = OrderConfirmed(
            orderId = OrderId(orderId),
            skuId = SkuId(skuId)
        )

        assert(event.orderId.value == orderId) { "orderId mismatch" }
        assert(event.skuId.value == skuId) { "skuId mismatch" }
    }

    @Test
    fun `OrderConfirmed has occurredAt timestamp`() {
        val before = Instant.now()
        val event = OrderConfirmed(
            orderId = OrderId(UUID.randomUUID()),
            skuId = SkuId(UUID.randomUUID())
        )
        val after = Instant.now()

        assert(!event.occurredAt.isBefore(before)) { "occurredAt should be >= before" }
        assert(!event.occurredAt.isAfter(after)) { "occurredAt should be <= after" }
    }

    @Test
    fun `OrderConfirmed follows OrderFulfilled event pattern`() {
        // Both OrderConfirmed and OrderFulfilled should have the same shape
        val orderId = OrderId(UUID.randomUUID())
        val skuId = SkuId(UUID.randomUUID())

        val confirmed = OrderConfirmed(orderId = orderId, skuId = skuId)
        val fulfilled = OrderFulfilled(orderId = orderId, skuId = skuId)

        assert(confirmed.orderId == fulfilled.orderId) { "Same orderId pattern" }
        assert(confirmed.skuId == fulfilled.skuId) { "Same skuId pattern" }
    }
}
