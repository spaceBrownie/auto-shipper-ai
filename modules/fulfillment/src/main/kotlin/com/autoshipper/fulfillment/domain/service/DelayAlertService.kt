package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.proxy.notification.NotificationSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DelayAlertService(
    private val notificationSender: NotificationSender
) {
    private val logger = LoggerFactory.getLogger(DelayAlertService::class.java)

    fun checkAndAlert(order: Order) {
        if (!order.shipmentDetails.delayDetected) return

        val trackingNumber = order.shipmentDetails.trackingNumber ?: "unknown"
        val estimatedDelivery = order.shipmentDetails.estimatedDelivery?.toString() ?: "unknown"

        val message = "Your order ${order.id} (tracking: $trackingNumber) has been delayed. " +
            "New estimated delivery: $estimatedDelivery. We apologize for the inconvenience."

        notificationSender.send(
            orderId = order.id,
            type = "DELAY_ALERT",
            message = message
        )

        logger.warn("Delay alert sent for order {} (tracking: {})", order.id, trackingNumber)
    }
}
