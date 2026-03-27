package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.channel.ChannelOrder
import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import com.autoshipper.fulfillment.proxy.platform.VendorSkuResolver
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Processes a single line item in its own REQUIRES_NEW transaction.
 *
 * Separated from ShopifyOrderProcessingService so Spring's proxy-based AOP
 * can intercept the call. Each line item gets an independent transaction —
 * a failure on one line item (e.g., inventory unavailable) does not roll back
 * orders already created for other line items.
 */
@Component
class LineItemOrderCreator(
    private val platformListingResolver: PlatformListingResolver,
    private val vendorSkuResolver: VendorSkuResolver,
    private val orderService: OrderService
) {
    private val logger = LoggerFactory.getLogger(LineItemOrderCreator::class.java)

    /**
     * @return true if an order was created, false if skipped or already existed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processLineItem(
        index: Int,
        lineItem: com.autoshipper.fulfillment.domain.channel.ChannelLineItem,
        channelOrder: ChannelOrder,
        customerUUID: UUID,
        currency: Currency
    ): Boolean {
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
            return false
        }

        val vendorId = vendorSkuResolver.resolveVendorId(skuId)
        if (vendorId == null) {
            logger.warn("No vendor assignment for SKU {}", skuId)
            return false
        }

        val totalAmount = Money.of(
            lineItem.unitPrice.multiply(lineItem.quantity.toBigDecimal()),
            currency
        )

        val shippingAddr = channelOrder.shippingAddress?.let { addr ->
            ShippingAddress(
                customerName = addr.customerName,
                address = listOfNotNull(addr.address1, addr.address2).joinToString(", "),
                city = addr.city,
                province = addr.province,
                country = addr.country,
                countryCode = addr.countryCode,
                zip = addr.zip,
                phone = addr.phone
            )
        }

        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerUUID,
            totalAmount = totalAmount,
            paymentIntentId = "shopify:order:${channelOrder.channelOrderId}",
            idempotencyKey = "shopify:order:${channelOrder.channelOrderId}:item:$index",
            shippingAddress = shippingAddr,
            quantity = lineItem.quantity
        )

        val (order, isNew) = orderService.create(command)

        if (isNew) {
            orderService.setChannelMetadata(
                orderId = order.id,
                channel = channelOrder.channelName,
                channelOrderId = channelOrder.channelOrderId,
                channelOrderNumber = channelOrder.channelOrderNumber
            )
            logger.info(
                "Created order {} for SKU {} from Shopify order {}",
                order.id, skuId, channelOrder.channelOrderId
            )
            return true
        } else {
            logger.info(
                "Order already exists for idempotency key {}, skipping channel metadata",
                command.idempotencyKey
            )
            return false
        }
    }
}
