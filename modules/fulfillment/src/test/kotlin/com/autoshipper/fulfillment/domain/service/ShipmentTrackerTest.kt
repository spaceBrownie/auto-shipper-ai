package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.carrier.CarrierTrackingProvider
import com.autoshipper.fulfillment.proxy.carrier.TrackingStatus
import com.autoshipper.shared.events.OrderFulfilled
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentTrackerTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var delayAlertService: DelayAlertService

    @Mock
    lateinit var upsProvider: CarrierTrackingProvider

    @Mock
    lateinit var fedexProvider: CarrierTrackingProvider

    private lateinit var shipmentTracker: ShipmentTracker

    private fun shippedOrder(
        carrier: String? = "UPS",
        trackingNumber: String? = "1Z999"
    ): Order = Order(
        idempotencyKey = "idem-${UUID.randomUUID()}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        status = OrderStatus.SHIPPED,
        shipmentDetails = ShipmentDetails(
            trackingNumber = trackingNumber,
            carrier = carrier,
            estimatedDelivery = Instant.now().plusSeconds(172800),
            lastKnownLocation = "Origin facility",
            delayDetected = false
        )
    )

    @BeforeEach
    fun setUp() {
        whenever(upsProvider.carrierName).thenReturn("UPS")
        whenever(fedexProvider.carrierName).thenReturn("FedEx")
        shipmentTracker = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = listOf(upsProvider, fedexProvider),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )
    }

    @Test
    fun `pollAllShipments updates tracking details from carrier`() {
        val order = shippedOrder(carrier = "UPS", trackingNumber = "1Z999")
        val trackingStatus = TrackingStatus(
            currentLocation = "Distribution center",
            estimatedDelivery = Instant.now().plusSeconds(86400),
            delivered = false,
            delayed = false
        )

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))
        whenever(upsProvider.getTrackingStatus("1Z999")).thenReturn(trackingStatus)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        shipmentTracker.pollAllShipments()

        verify(orderRepository).save(argThat<Order> {
            this.shipmentDetails.lastKnownLocation == "Distribution center"
        })
    }

    @Test
    fun `pollAllShipments marks delivered orders and publishes event`() {
        val order = shippedOrder(carrier = "UPS", trackingNumber = "1Z999")
        val trackingStatus = TrackingStatus(
            currentLocation = "Delivered",
            estimatedDelivery = null,
            delivered = true,
            delayed = false
        )

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))
        whenever(upsProvider.getTrackingStatus("1Z999")).thenReturn(trackingStatus)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        shipmentTracker.pollAllShipments()

        verify(orderRepository).save(argThat<Order> {
            this.status == OrderStatus.DELIVERED
        })
        verify(eventPublisher).publishEvent(argThat<OrderFulfilled> {
            this.orderId.value == order.id
        })
    }

    @Test
    fun `pollAllShipments detects delays and triggers alert`() {
        val order = shippedOrder(carrier = "FedEx", trackingNumber = "FX123")
        val trackingStatus = TrackingStatus(
            currentLocation = "Stuck in transit",
            estimatedDelivery = Instant.now().plusSeconds(259200),
            delivered = false,
            delayed = true
        )

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))
        whenever(fedexProvider.getTrackingStatus("FX123")).thenReturn(trackingStatus)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        shipmentTracker.pollAllShipments()

        verify(delayAlertService).checkAndAlert(argThat<Order> {
            this.shipmentDetails.delayDetected
        })
        verify(eventPublisher, never()).publishEvent(any<OrderFulfilled>())
    }

    @Test
    fun `pollAllShipments skips orders with unknown carrier`() {
        val order = shippedOrder(carrier = "DHL", trackingNumber = "DHL123")

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))

        shipmentTracker.pollAllShipments()

        verify(upsProvider, never()).getTrackingStatus(any())
        verify(fedexProvider, never()).getTrackingStatus(any())
        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `pollAllShipments skips orders with null carrier`() {
        val order = shippedOrder(carrier = null, trackingNumber = "TRACK")

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))

        shipmentTracker.pollAllShipments()

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `pollAllShipments skips orders with null tracking number`() {
        val order = shippedOrder(carrier = "UPS", trackingNumber = null)

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order))

        shipmentTracker.pollAllShipments()

        verify(upsProvider, never()).getTrackingStatus(any())
        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `pollAllShipments handles empty shipped orders list`() {
        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(emptyList())

        shipmentTracker.pollAllShipments()

        verify(orderRepository, never()).save(any<Order>())
        verify(eventPublisher, never()).publishEvent(any<OrderFulfilled>())
    }

    @Test
    fun `pollAllShipments continues processing when one order throws`() {
        val order1 = shippedOrder(carrier = "UPS", trackingNumber = "1Z111")
        val order2 = shippedOrder(carrier = "UPS", trackingNumber = "1Z222")
        val trackingStatus = TrackingStatus(
            currentLocation = "In transit",
            estimatedDelivery = null,
            delivered = false,
            delayed = false
        )

        whenever(orderRepository.findByStatus(OrderStatus.SHIPPED)).thenReturn(listOf(order1, order2))
        whenever(upsProvider.getTrackingStatus("1Z111")).thenThrow(RuntimeException("API error"))
        whenever(upsProvider.getTrackingStatus("1Z222")).thenReturn(trackingStatus)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        shipmentTracker.pollAllShipments()

        // Second order should still be processed despite first one failing
        verify(orderRepository, times(1)).save(any<Order>())
    }
}
