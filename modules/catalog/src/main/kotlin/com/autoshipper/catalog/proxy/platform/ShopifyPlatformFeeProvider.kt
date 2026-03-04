package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

/**
 * Retrieves the Shopify platform fee based on the current store plan.
 *
 * Shopify transaction fees by plan (when not using Shopify Payments):
 *   - Basic: 2.0%
 *   - Shopify: 1.0%
 *   - Advanced: 0.5%
 *   - Plus: 0.0%
 */
@Component
@Profile("!local")
class ShopifyPlatformFeeProvider(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient,
    @Value("\${shopify.api.access-token}") private val accessToken: String,
    @Value("\${shopify.platform.estimated-order-value:100.00}") private val estimatedOrderValue: BigDecimal
) : PlatformFeeProvider {
    companion object {
        private val PLAN_FEE_RATES: Map<String, BigDecimal> = mapOf(
            "basic" to BigDecimal("0.020"),
            "shopify" to BigDecimal("0.010"),
            "advanced" to BigDecimal("0.005"),
            "plus" to BigDecimal("0.000")
        )
        private val DEFAULT_RATE = BigDecimal("0.020")  // Assume Basic if unknown
    }

    @CircuitBreaker(name = "shopify-fee")
    @Retry(name = "shopify-fee")
    override fun getFee(): Money {
        try {
            @Suppress("UNCHECKED_CAST")
            val response = shopifyRestClient.get()
                .uri("/admin/api/2024-01/shop.json")
                .header("X-Shopify-Access-Token", accessToken)
                .retrieve()
                .body(Map::class.java) as Map<String, Any>?
                ?: throw RuntimeException("Empty response from Shopify shop API")

            val shop = response["shop"] as? Map<String, Any>
                ?: throw RuntimeException("Missing shop object in Shopify response")

            val planName = (shop["plan_name"] as? String)?.lowercase() ?: "basic"
            val feeRate = PLAN_FEE_RATES[planName] ?: DEFAULT_RATE

            val feeAmount = estimatedOrderValue.multiply(feeRate)
            return Money.of(feeAmount, Currency.USD)
        } catch (ex: Exception) {
            throw ProviderUnavailableException("Shopify", ex)
        }
    }
}
