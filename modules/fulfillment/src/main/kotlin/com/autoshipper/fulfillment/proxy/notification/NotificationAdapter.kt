package com.autoshipper.fulfillment.proxy.notification

import com.autoshipper.fulfillment.domain.CustomerNotification
import com.autoshipper.fulfillment.persistence.CustomerNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

interface NotificationSender {
    fun send(orderId: UUID, type: String, message: String)
}

/**
 * Phase 1 stub: persists notifications to the customer_notifications table
 * and logs them. Replace with a real email/SMS provider in Phase 2.
 */
@Component
class LoggingNotificationAdapter(
    private val notificationRepository: CustomerNotificationRepository
) : NotificationSender {

    private val logger = LoggerFactory.getLogger(LoggingNotificationAdapter::class.java)

    override fun send(orderId: UUID, type: String, message: String) {
        val notification = CustomerNotification(
            orderId = orderId,
            notificationType = type,
            message = message
        )
        notificationRepository.save(notification)
        logger.info("Notification [{}] for order {}: {}", type, orderId, message)
    }
}
