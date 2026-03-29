package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.channel.ShopifyOrderAdapter
import com.autoshipper.fulfillment.handler.webhook.ShopifyOrderReceivedEvent
import com.autoshipper.shared.money.Currency
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Orchestrates Shopify order webhook processing asynchronously after the
 * deduplication record is committed. Each line item is processed in its own
 * REQUIRES_NEW transaction via LineItemOrderCreator — a failure on one line
 * item does not roll back orders created for other line items.
 *
 * @Async ensures processing runs on a separate thread so the HTTP 200
 * response returns to Shopify immediately (within 5-second timeout).
 *
 * Uses @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)
 * per CLAUDE.md constraint #6. @Async is compatible — the REQUIRES_NEW
 * transaction simply runs on the async thread instead of the commit thread.
 */
@Component
class ShopifyOrderProcessingService(
    private val shopifyOrderAdapter: ShopifyOrderAdapter,
    private val lineItemOrderCreator: LineItemOrderCreator
) {
    private val logger = LoggerFactory.getLogger(ShopifyOrderProcessingService::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderReceived(event: ShopifyOrderReceivedEvent) {
        val channelOrder = shopifyOrderAdapter.parse(event.rawPayload)

        val customerUUID = UUID.nameUUIDFromBytes(channelOrder.customerEmail.toByteArray())
        val currency = try {
            Currency.valueOf(channelOrder.currencyCode)
        } catch (e: IllegalArgumentException) {
            logger.error(
                "Unsupported currency '{}' in Shopify order {} — skipping all line items",
                channelOrder.currencyCode, channelOrder.channelOrderId
            )
            return
        }

        var resolved = 0
        var created = 0

        channelOrder.lineItems.forEachIndexed { index, lineItem ->
            try {
                val wasCreated = lineItemOrderCreator.processLineItem(
                    index, lineItem, channelOrder, customerUUID, currency,
                    shippingAddress = channelOrder.shippingAddress
                )
                if (wasCreated) created++
                resolved++
            } catch (e: Exception) {
                logger.error(
                    "Failed to process line item {} in Shopify order {}: {}",
                    index, channelOrder.channelOrderId, e.message
                )
            }
        }

        logger.info(
            "Processed Shopify order {}: {} of {} line items resolved, {} orders created",
            channelOrder.channelOrderId, resolved, channelOrder.lineItems.size, created
        )
    }
}
