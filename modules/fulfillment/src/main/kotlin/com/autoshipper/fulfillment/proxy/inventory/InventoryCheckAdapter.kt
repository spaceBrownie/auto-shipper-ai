package com.autoshipper.fulfillment.proxy.inventory

import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

interface InventoryChecker {
    fun isAvailable(skuId: UUID): Boolean
}

/**
 * Checks Shopify inventory availability for an internal SKU by resolving its persisted
 * `shopify_inventory_item_id` (written at listing time) via PlatformListingResolver, then
 * calling Shopify's inventory_levels endpoint. Returns false conservatively when no
 * mapping exists — prevents us from treating an unmapped SKU as in-stock.
 */
@Component
@Profile("!local")
class ShopifyInventoryCheckAdapter(
    @Value("\${shopify.api.base-url:}") private val baseUrl: String,
    @Value("\${shopify.api.access-token:}") private val accessToken: String,
    private val resolver: PlatformListingResolver
) : InventoryChecker {

    private val logger = LoggerFactory.getLogger(ShopifyInventoryCheckAdapter::class.java)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("X-Shopify-Access-Token", accessToken)
        .defaultHeader("Content-Type", "application/json")
        .build()

    @CircuitBreaker(name = "shopify-inventory")
    @Retry(name = "shopify-inventory")
    override fun isAvailable(skuId: UUID): Boolean {
        val inventoryItemId = resolver.resolveInventoryItemId(skuId)
        if (inventoryItemId == null) {
            logger.warn(
                "ShopifyInventoryCheckAdapter: no inventory_item_id mapped for sku={} — returning false (conservative default)",
                skuId
            )
            return false
        }

        val response = restClient.get()
            .uri("/admin/api/2024-01/inventory_levels.json?inventory_item_ids={id}", inventoryItemId)
            .retrieve()
            .body(Map::class.java)
            ?: return false

        @Suppress("UNCHECKED_CAST")
        val levels = response["inventory_levels"] as? List<Map<String, Any>> ?: return false
        return levels.any { level ->
            val available = level["available"] as? Number ?: return@any false
            available.toInt() > 0
        }
    }
}

@Configuration
@Profile("local")
class StubInventoryConfiguration {

    @Bean
    fun stubInventoryChecker(): InventoryChecker = object : InventoryChecker {
        override fun isAvailable(skuId: UUID): Boolean = true
    }
}
