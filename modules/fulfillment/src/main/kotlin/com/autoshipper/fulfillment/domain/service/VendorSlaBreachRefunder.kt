package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.notification.NotificationSender
import com.autoshipper.fulfillment.proxy.payment.RefundProvider
import com.autoshipper.shared.events.VendorSlaBreached
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class VendorSlaBreachRefunder(
    private val orderRepository: OrderRepository,
    private val refundProvider: RefundProvider,
    private val notificationSender: NotificationSender
) {
    private val logger = LoggerFactory.getLogger(VendorSlaBreachRefunder::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onVendorSlaBreached(event: VendorSlaBreached) {
        logger.warn(
            "Vendor SLA breached for vendor {}. Breach rate: {}. Initiating refunds for active orders.",
            event.vendorId, event.breachRate
        )

        val activeStatuses = listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED)
        val affectedOrders = orderRepository.findByVendorIdAndStatusIn(
            event.vendorId.value,
            activeStatuses
        )

        logger.info("Found {} active orders for breached vendor {}", affectedOrders.size, event.vendorId)

        for (order in affectedOrders) {
            try {
                val idempotencyKey = "sla_breach_refund_${order.id}"
                val refundAmount = order.totalAmount()

                require(refundAmount.normalizedAmount > java.math.BigDecimal.ZERO) {
                    "Order ${order.id} has zero total amount — cannot issue refund"
                }

                val result = refundProvider.refund(
                    orderId = order.id,
                    amount = refundAmount,
                    paymentIntentId = order.paymentIntentId,
                    idempotencyKey = idempotencyKey
                )

                order.updateStatus(OrderStatus.REFUNDED)
                orderRepository.save(order)

                notificationSender.send(
                    orderId = order.id,
                    type = "SLA_BREACH_REFUND",
                    message = "Your order ${order.id} has been refunded (refund ID: ${result.refundId}) " +
                        "due to a vendor fulfillment issue. We apologize for the inconvenience."
                )

                logger.info(
                    "Order {} refunded (refund ID: {}) due to vendor {} SLA breach",
                    order.id, result.refundId, event.vendorId
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to refund order {} for vendor {} SLA breach",
                    order.id, event.vendorId, e
                )
            }
        }
    }
}
