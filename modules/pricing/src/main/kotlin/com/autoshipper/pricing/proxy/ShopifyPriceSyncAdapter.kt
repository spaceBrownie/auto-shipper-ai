package com.autoshipper.pricing.proxy

import com.autoshipper.catalog.persistence.PlatformListingRepository
import com.autoshipper.catalog.proxy.platform.PlatformAdapter
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

@Component
@Profile("!local")
class ShopifyPriceSyncAdapter(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient,
    private val platformAdapter: PlatformAdapter,
    private val platformListingRepository: PlatformListingRepository,
    @Value("\${shopify.api.access-token:}") private val accessToken: String
) : PriceSyncAdapter {
    private val log = LoggerFactory.getLogger(ShopifyPriceSyncAdapter::class.java)

    @Retry(name = "shopify-price-sync")
    override fun syncPrice(skuId: SkuId, newPrice: Money) {
        log.info("Syncing price for SKU {} to Shopify: {}", skuId, newPrice)

        val listing = platformListingRepository.findBySkuId(skuId.value)
        val variantId = listing?.externalVariantId
        if (listing != null && variantId != null) {
            // Delegate to PlatformAdapter for SKUs with platform listings
            platformAdapter.updatePrice(variantId, newPrice)
            listing.currentPriceAmount = newPrice.normalizedAmount
            listing.updatedAt = Instant.now()
            platformListingRepository.save(listing)
            log.info("Successfully synced price for SKU {} via PlatformAdapter", skuId)
        } else {
            // Backward compat: direct Shopify PUT for SKUs listed before FR-020
            log.info("No platform listing for SKU {}; falling back to direct Shopify variant PUT", skuId)
            shopifyRestClient.put()
                .uri("/admin/api/2024-01/variants/{variantId}.json", skuId.value)
                .header("X-Shopify-Access-Token", accessToken)
                .body(mapOf("variant" to mapOf("price" to newPrice.normalizedAmount.toPlainString())))
                .retrieve()
                .toBodilessEntity()
            log.info("Successfully synced price for SKU {} to Shopify (direct)", skuId)
        }
    }
}
