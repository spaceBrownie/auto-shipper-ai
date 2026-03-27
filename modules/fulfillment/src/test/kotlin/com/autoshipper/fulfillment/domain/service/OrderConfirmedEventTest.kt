package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
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
 * Tests that OrderService.routeToVendor() publishes an OrderConfirmed domain event.
 *
 * These tests will FAIL until routeToVendor() is updated to call
 * eventPublisher.publishEvent(OrderConfirmed(...)) after transitioning to CONFIRMED.
 */
@ExtendWith(MockitoExtension::class)
class OrderConfirmedEventTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var orderService: OrderService

    private val skuId = UUID.randomUUID()

    private fun pendingOrder(): Order = Order(
        idempotencyKey = "test-idem-${UUID.randomUUID()}",
        skuId = skuId,
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.99"),
        totalCurrency = Currency.USD,
        quantity = 1,
        paymentIntentId = "pi_test_123",
        status = OrderStatus.PENDING
    )

    // --- SC-7 / AD-1: routeToVendor publishes OrderConfirmed ---

    @Test
    fun `routeToVendor publishes OrderConfirmed event after CONFIRMED transition`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        orderService.routeToVendor(order.id)

        // Verify OrderConfirmed is published with correct orderId and skuId
        verify(eventPublisher).publishEvent(argThat<OrderConfirmed> {
            this.orderId.value == order.id && this.skuId.value == order.skuId
        })
    }

    @Test
    fun `routeToVendor publishes event with matching orderId`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        orderService.routeToVendor(order.id)

        val captor = argumentCaptor<OrderConfirmed>()
        verify(eventPublisher).publishEvent(captor.capture())
        val event = captor.firstValue

        assert(event.orderId.value == order.id) {
            "OrderConfirmed.orderId must match the routed order's ID"
        }
        assert(event.skuId.value == order.skuId) {
            "OrderConfirmed.skuId must match the order's SKU ID"
        }
    }
}
