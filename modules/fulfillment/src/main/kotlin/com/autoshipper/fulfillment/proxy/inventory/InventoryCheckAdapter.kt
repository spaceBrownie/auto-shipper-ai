package com.autoshipper.fulfillment.proxy.inventory

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
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

@Component
@Profile("!local")
class ShopifyInventoryCheckAdapter(
    @Value("\${shopify.api.base-url:}") private val baseUrl: String,
    @Value("\${shopify.api.access-token:}") private val accessToken: String
) : InventoryChecker {

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("X-Shopify-Access-Token", accessToken)
        .defaultHeader("Content-Type", "application/json")
        .build()

    @CircuitBreaker(name = "shopify-inventory")
    @Retry(name = "shopify-inventory")
    override fun isAvailable(skuId: UUID): Boolean {
        val response = restClient.get()
            .uri("/admin/api/2024-01/inventory_levels.json?inventory_item_ids={skuId}", skuId)
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
