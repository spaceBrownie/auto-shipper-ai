package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.channel.ShopifyOrderAdapter
import com.autoshipper.fulfillment.handler.webhook.ShopifyOrderReceivedEvent
import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import com.autoshipper.fulfillment.proxy.platform.VendorSkuResolver
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Processes Shopify order webhook events asynchronously after the deduplication
 * record is committed. Creates internal Order entities for each resolvable line item.
 *
 * Uses @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)
 * per CLAUDE.md constraint #6 — without REQUIRES_NEW, writes are silently discarded.
 */
@Component
class ShopifyOrderProcessingService(
    private val shopifyOrderAdapter: ShopifyOrderAdapter,
    private val platformListingResolver: PlatformListingResolver,
    private val vendorSkuResolver: VendorSkuResolver,
    private val orderService: OrderService
) {
    private val logger = LoggerFactory.getLogger(ShopifyOrderProcessingService::class.java)

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
            val skuId = platformListingResolver.resolveSkuId(
                lineItem.externalProductId,
                lineItem.externalVariantId,
                "SHOPIFY"
            )
            if (skuId == null) {
                logger.warn(
                    "Unresolvable line item: productId={}, variantId={}, title={}",
                    lineItem.externalProductId, lineItem.externalVariantId, lineItem.title
                )
                return@forEachIndexed
            }

            val vendorId = vendorSkuResolver.resolveVendorId(skuId)
            if (vendorId == null) {
                logger.warn("No vendor assignment for SKU {}", skuId)
                return@forEachIndexed
            }

            resolved++

            val totalAmount = Money.of(
                lineItem.unitPrice.multiply(lineItem.quantity.toBigDecimal()),
                currency
            )

            val command = CreateOrderCommand(
                skuId = skuId,
                vendorId = vendorId,
                customerId = customerUUID,
                totalAmount = totalAmount,
                paymentIntentId = "shopify:order:${channelOrder.channelOrderId}",
                idempotencyKey = "shopify:order:${channelOrder.channelOrderId}:item:$index"
            )

            val (order, isNew) = orderService.create(command)

            if (isNew) {
                orderService.setChannelMetadata(
                    orderId = order.id,
                    channel = channelOrder.channelName,
                    channelOrderId = channelOrder.channelOrderId,
                    channelOrderNumber = channelOrder.channelOrderNumber
                )
                created++
                logger.info(
                    "Created order {} for SKU {} from Shopify order {}",
                    order.id, skuId, channelOrder.channelOrderId
                )
            } else {
                logger.info(
                    "Order already exists for idempotency key {}, skipping channel metadata",
                    command.idempotencyKey
                )
            }
        }

        logger.info(
            "Processed Shopify order {}: {} of {} line items resolved, {} orders created",
            channelOrder.channelOrderId, resolved, channelOrder.lineItems.size, created
        )
    }
}
