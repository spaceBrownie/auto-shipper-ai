package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.platform.ShopifyFulfillmentPort
import com.autoshipper.shared.events.OrderShipped
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for OrderShipped events and syncs the fulfillment to Shopify.
 *
 * Follows CLAUDE.md #6: AFTER_COMMIT + REQUIRES_NEW for cross-module event listeners
 * that write to the database (or call external APIs after commit).
 *
 * Shopify sync failure is non-fatal: the order is already SHIPPED in our system.
 * All exceptions are caught and logged — we do not rethrow to avoid poisoning
 * the event listener chain.
 */
@Component
class ShopifyFulfillmentSyncListener(
    private val shopifyFulfillmentPort: ShopifyFulfillmentPort,
    private val orderRepository: OrderRepository
) {
    private val logger = LoggerFactory.getLogger(ShopifyFulfillmentSyncListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderShipped(event: OrderShipped) {
        logger.info("OrderShipped received for order {}, syncing fulfillment to Shopify", event.orderId)

        val order = orderRepository.findById(event.orderId.value).orElse(null)
        if (order == null) {
            logger.warn("Order {} not found, skipping Shopify fulfillment sync", event.orderId)
            return
        }

        val channelOrderId = order.channelOrderId
        if (channelOrderId == null) {
            logger.warn("Order {} has no channelOrderId, skipping Shopify fulfillment sync", event.orderId)
            return
        }

        try {
            val success = shopifyFulfillmentPort.createFulfillment(
                shopifyOrderId = channelOrderId,
                trackingNumber = event.trackingNumber,
                carrier = event.carrier
            )
            if (success) {
                logger.info("Shopify fulfillment synced for order {} (channelOrderId={})", event.orderId, channelOrderId)
            } else {
                logger.warn(
                    "Shopify fulfillment sync returned false for order {} (channelOrderId={})",
                    event.orderId, channelOrderId
                )
            }
        } catch (e: Exception) {
            logger.warn(
                "Shopify fulfillment sync failed for order {} (channelOrderId={}): {}",
                event.orderId, channelOrderId, e.message
            )
        }
    }
}
