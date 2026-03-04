package com.autoshipper.pricing.proxy

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class ShopifyPriceSyncAdapter(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient
) {
    private val log = LoggerFactory.getLogger(ShopifyPriceSyncAdapter::class.java)

    @Retry(name = "shopify-price-sync")
    fun syncPrice(skuId: SkuId, newPrice: Money) {
        log.info("Syncing price for SKU {} to Shopify: {}", skuId, newPrice)

        shopifyRestClient.put()
            .uri("/admin/api/2024-01/variants/{variantId}.json", skuId.value)
            .body(mapOf("variant" to mapOf("price" to newPrice.normalizedAmount.toPlainString())))
            .retrieve()
            .toBodilessEntity()

        log.info("Successfully synced price for SKU {} to Shopify", skuId)
    }
}
