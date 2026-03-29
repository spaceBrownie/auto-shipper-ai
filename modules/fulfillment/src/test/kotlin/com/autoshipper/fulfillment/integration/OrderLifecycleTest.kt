package com.autoshipper.fulfillment.integration

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.DelayAlertService
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.domain.service.ShipmentTracker
import com.autoshipper.fulfillment.domain.service.VendorSlaBreachRefunder
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.carrier.CarrierTrackingProvider
import com.autoshipper.fulfillment.proxy.carrier.TrackingStatus
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.fulfillment.proxy.notification.NotificationSender
import com.autoshipper.fulfillment.proxy.payment.RefundProvider
import com.autoshipper.fulfillment.proxy.payment.RefundResult
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.events.OrderFulfilled
import com.autoshipper.shared.events.VendorSlaBreached
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Full order lifecycle integration test using Mockito to simulate the complete flow
 * from order creation through delivery or SLA breach refund.
 */
@ExtendWith(MockitoExtension::class)
class OrderLifecycleTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var notificationSender: NotificationSender

    @Mock
    lateinit var refundProvider: RefundProvider

    @Mock
    lateinit var carrierProvider: CarrierTrackingProvider

    @Mock
    lateinit var delayAlertService: DelayAlertService

    private val skuId = UUID.randomUUID()
    private val vendorUUID = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    /**
     * Helper: stores saved orders in a map to simulate repository behavior.
     */
    private fun setupRepositoryWithInMemoryStore(): MutableMap<UUID, Order> {
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
    fun `full lifecycle - create, route, ship, deliver - publishes OrderFulfilled`() {
        val store = setupRepositoryWithInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create order
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorUUID,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("59.99"), Currency.USD),
            paymentIntentId = "pi_lifecycle_1",
            idempotencyKey = "lifecycle-test-1",
            quantity = 1
        )
        val (order, created) = orderService.create(command)
        assert(created)
        assert(order.status == OrderStatus.PENDING)

        // Step 2: Route to vendor
        val confirmed = orderService.routeToVendor(order.id)
        assert(confirmed.status == OrderStatus.CONFIRMED)

        // Step 3: Mark shipped
        val shipped = orderService.markShipped(order.id, "1Z999AA1", "UPS")
        assert(shipped.status == OrderStatus.SHIPPED)
        assert(shipped.shipmentDetails.trackingNumber == "1Z999AA1")

        // Step 4: Simulate delivery via ShipmentTracker
        whenever(carrierProvider.carrierName).thenReturn("UPS")
        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(store[order.id]!!))
        whenever(carrierProvider.getTrackingStatus("1Z999AA1")).thenReturn(
            TrackingStatus(
                currentLocation = "Delivered to front door",
                estimatedDelivery = null,
                delivered = true,
                delayed = false
            )
        )

        val shipmentTracker = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = listOf(carrierProvider),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )
        shipmentTracker.pollAllShipments()

        // Verify delivered
        assert(store[order.id]!!.status == OrderStatus.DELIVERED)

        // routeToVendor publishes OrderConfirmed, pollAllShipments publishes OrderFulfilled
        val captor = argumentCaptor<Any>()
        verify(eventPublisher, atLeast(2)).publishEvent(captor.capture())
        val confirmedEvents = captor.allValues.filterIsInstance<OrderConfirmed>()
        assert(confirmedEvents.size == 1 && confirmedEvents[0].orderId.value == order.id) {
            "Expected exactly one OrderConfirmed event for order ${order.id}"
        }
        val fulfilledEvents = captor.allValues.filterIsInstance<OrderFulfilled>()
        assert(fulfilledEvents.size == 1 && fulfilledEvents[0].orderId.value == order.id) {
            "Expected exactly one OrderFulfilled event for order ${order.id}"
        }
    }

    @Test
    fun `SLA breach lifecycle - create orders then vendor breach triggers refunds`() {
        val store = setupRepositoryWithInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Create and confirm two orders
        val amount = Money.of(BigDecimal("39.99"), Currency.USD)
        val cmd1 = CreateOrderCommand(skuId, vendorUUID, customerId, amount, "pi_breach_1", "breach-test-1", quantity = 1)
        val (order1, _) = orderService.create(cmd1)
        orderService.routeToVendor(order1.id)

        val cmd2 = CreateOrderCommand(skuId, vendorUUID, customerId, amount, "pi_breach_2", "breach-test-2", quantity = 1)
        val (order2, _) = orderService.create(cmd2)
        orderService.routeToVendor(order2.id)

        // Setup vendor SLA breach
        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(store.values.filter { it.status == OrderStatus.CONFIRMED }.toList())

        whenever(refundProvider.refund(any(), any<Money>(), any(), any())).thenReturn(
            RefundResult(refundId = "ref-breach", status = "succeeded")
        )

        val refunder = VendorSlaBreachRefunder(orderRepository, refundProvider, notificationSender)

        val event = VendorSlaBreached(
            vendorId = VendorId(vendorUUID),
            skuIds = listOf(SkuId(skuId)),
            breachRate = Percentage.of(20)
        )
        refunder.onVendorSlaBreached(event)

        // Both orders should be refunded
        verify(refundProvider, times(2)).refund(any(), any<Money>(), any(), any())
        verify(notificationSender, times(2)).send(any(), eq("SLA_BREACH_REFUND"), any())
    }

    @Test
    fun `create order with no inventory rejects order`() {
        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(false)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorUUID,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("19.99"), Currency.USD),
            paymentIntentId = "pi_no_inv",
            idempotencyKey = "no-inventory-test",
            quantity = 1
        )

        assertThrows<IllegalArgumentException> {
            orderService.create(command)
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `delay detection lifecycle - shipment delayed triggers alert`() {
        val store = setupRepositoryWithInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Create, route, and ship
        val cmd = CreateOrderCommand(skuId, vendorUUID, customerId, Money.of(BigDecimal("29.99"), Currency.USD), "pi_delay_1", "delay-test-1", quantity = 1)
        val (order, _) = orderService.create(cmd)
        orderService.routeToVendor(order.id)
        orderService.markShipped(order.id, "DELAY-TRACK", "UPS")

        // Simulate delay via ShipmentTracker
        whenever(carrierProvider.carrierName).thenReturn("UPS")
        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(store[order.id]!!))
        whenever(carrierProvider.getTrackingStatus("DELAY-TRACK")).thenReturn(
            TrackingStatus(
                currentLocation = "Stuck at customs",
                estimatedDelivery = Instant.now().plusSeconds(604800),
                delivered = false,
                delayed = true
            )
        )

        val realDelayAlertService = DelayAlertService(notificationSender)
        val shipmentTracker = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = listOf(carrierProvider),
            eventPublisher = eventPublisher,
            delayAlertService = realDelayAlertService
        )
        shipmentTracker.pollAllShipments()

        // Verify delay was detected and notification sent
        assert(store[order.id]!!.shipmentDetails.delayDetected)
        verify(notificationSender).send(eq(order.id), eq("DELAY_ALERT"), argThat { contains("delayed") })
        verify(eventPublisher, never()).publishEvent(any<OrderFulfilled>())
    }
}
