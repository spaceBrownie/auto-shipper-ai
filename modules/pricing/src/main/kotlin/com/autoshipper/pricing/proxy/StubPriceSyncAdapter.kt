package com.autoshipper.pricing.proxy

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class StubPriceSyncAdapter : PriceSyncAdapter {
    private val log = LoggerFactory.getLogger(StubPriceSyncAdapter::class.java)

    override fun syncPrice(skuId: SkuId, newPrice: Money) {
        log.info("[STUB] Would sync price for SKU {} to Shopify: {}", skuId, newPrice)
    }
}
