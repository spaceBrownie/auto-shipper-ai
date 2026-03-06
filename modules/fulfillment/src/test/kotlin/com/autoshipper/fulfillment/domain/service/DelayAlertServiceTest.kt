package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.proxy.notification.NotificationSender
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DelayAlertServiceTest {

    @Mock
    lateinit var notificationSender: NotificationSender

    @InjectMocks
    lateinit var delayAlertService: DelayAlertService

    private fun orderWithDelay(delayDetected: Boolean, trackingNumber: String? = "TRACK123"): Order = Order(
        idempotencyKey = "idem-${UUID.randomUUID()}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("29.9900"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_${UUID.randomUUID()}",
        status = OrderStatus.SHIPPED,
        shipmentDetails = ShipmentDetails(
            trackingNumber = trackingNumber,
            carrier = "UPS",
            estimatedDelivery = Instant.now().plusSeconds(86400),
            lastKnownLocation = "In transit",
            delayDetected = delayDetected
        )
    )

    @Test
    fun `checkAndAlert sends notification when delay detected`() {
        val order = orderWithDelay(delayDetected = true)

        delayAlertService.checkAndAlert(order)

        verify(notificationSender).send(
            eq(order.id),
            eq("DELAY_ALERT"),
            argThat { contains("delayed") }
        )
    }

    @Test
    fun `checkAndAlert does nothing when no delay`() {
        val order = orderWithDelay(delayDetected = false)

        delayAlertService.checkAndAlert(order)

        verify(notificationSender, never()).send(any(), any(), any())
    }

    @Test
    fun `checkAndAlert includes tracking number in message`() {
        val order = orderWithDelay(delayDetected = true, trackingNumber = "TRACK-ABC")

        delayAlertService.checkAndAlert(order)

        verify(notificationSender).send(
            eq(order.id),
            eq("DELAY_ALERT"),
            argThat { contains("TRACK-ABC") }
        )
    }

    @Test
    fun `checkAndAlert uses unknown when tracking number is null`() {
        val order = orderWithDelay(delayDetected = true, trackingNumber = null)

        delayAlertService.checkAndAlert(order)

        verify(notificationSender).send(
            eq(order.id),
            eq("DELAY_ALERT"),
            argThat { contains("unknown") }
        )
    }
}
