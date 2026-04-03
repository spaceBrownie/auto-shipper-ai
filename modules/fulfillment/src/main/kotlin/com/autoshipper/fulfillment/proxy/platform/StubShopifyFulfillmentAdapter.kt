package com.autoshipper.fulfillment.proxy.platform

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class StubShopifyFulfillmentAdapter : ShopifyFulfillmentPort {

    private val logger = LoggerFactory.getLogger(StubShopifyFulfillmentAdapter::class.java)

    override fun createFulfillment(shopifyOrderGid: String, trackingNumber: String, carrier: String): Boolean {
        logger.info(
            "[STUB] Would create Shopify fulfillment for order {} with tracking {} via {}",
            shopifyOrderGid, trackingNumber, carrier
        )
        return true
    }
}
