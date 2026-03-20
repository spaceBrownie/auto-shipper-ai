package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@Component
@Profile("!local")
class ShopifyListingAdapter(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient,
    @Value("\${shopify.api.access-token:}") private val accessToken: String,
    private val objectMapper: ObjectMapper
) : PlatformAdapter {
    private val log = LoggerFactory.getLogger(ShopifyListingAdapter::class.java)

    @CircuitBreaker(name = "shopify-listing")
    @Retry(name = "shopify-listing")
    override fun listSku(sku: LaunchReadySku, price: Money): PlatformListingResult {
        if (accessToken.isBlank()) {
            log.warn("Shopify access token is blank; cannot create listing for SKU {}", sku.sku.skuId())
            throw IllegalStateException("Shopify access token is not configured")
        }

        log.info("Creating Shopify listing for SKU {} with title '{}'", sku.sku.skuId(), sku.sku.name)

        val body = mapOf(
            "product" to mapOf(
                "title" to sku.sku.name,
                "product_type" to sku.sku.category,
                "status" to "active",
                "variants" to listOf(
                    mapOf(
                        "price" to price.normalizedAmount.toPlainString(),
                        "sku" to sku.sku.skuId().toString(),
                        "inventory_management" to "shopify"
                    )
                )
            )
        )

        val responseBody = shopifyRestClient.post()
            .uri("/admin/api/2024-01/products.json")
            .header("X-Shopify-Access-Token", accessToken)
            .body(body)
            .retrieve()
            .body(String::class.java)
            ?: throw RuntimeException("Empty response from Shopify product creation API")

        val root = objectMapper.readTree(responseBody)
        val product = root.get("product")
            ?: throw RuntimeException("Missing 'product' in Shopify response")

        val productId = product.get("id")?.asText()
            ?: throw RuntimeException("Missing 'product.id' in Shopify response")

        val variants = product.get("variants")
        val variantId = if (variants != null && variants.isArray && variants.size() > 0) {
            variants[0].get("id")?.asText()
        } else null

        log.info("Created Shopify listing for SKU {}: productId={}, variantId={}",
            sku.sku.skuId(), productId, variantId)

        return PlatformListingResult(
            externalListingId = productId,
            externalVariantId = variantId
        )
    }

    @CircuitBreaker(name = "shopify-listing")
    @Retry(name = "shopify-listing")
    override fun pauseSku(externalListingId: String) {
        if (accessToken.isBlank()) {
            log.warn("Shopify access token is blank; cannot pause listing {}", externalListingId)
            throw IllegalStateException("Shopify access token is not configured")
        }

        log.info("Pausing Shopify product {}", externalListingId)

        shopifyRestClient.put()
            .uri("/admin/api/2024-01/products/{productId}.json", externalListingId)
            .header("X-Shopify-Access-Token", accessToken)
            .body(mapOf("product" to mapOf("status" to "draft")))
            .retrieve()
            .toBodilessEntity()

        log.info("Successfully paused Shopify product {}", externalListingId)
    }

    @CircuitBreaker(name = "shopify-listing")
    @Retry(name = "shopify-listing")
    override fun archiveSku(externalListingId: String) {
        if (accessToken.isBlank()) {
            log.warn("Shopify access token is blank; cannot archive listing {}", externalListingId)
            throw IllegalStateException("Shopify access token is not configured")
        }

        log.info("Archiving Shopify product {}", externalListingId)

        shopifyRestClient.put()
            .uri("/admin/api/2024-01/products/{productId}.json", externalListingId)
            .header("X-Shopify-Access-Token", accessToken)
            .body(mapOf("product" to mapOf("status" to "archived")))
            .retrieve()
            .toBodilessEntity()

        log.info("Successfully archived Shopify product {}", externalListingId)
    }

    @CircuitBreaker(name = "shopify-listing")
    @Retry(name = "shopify-listing")
    override fun updatePrice(externalVariantId: String, newPrice: Money) {
        if (accessToken.isBlank()) {
            log.warn("Shopify access token is blank; cannot update price for variant {}", externalVariantId)
            throw IllegalStateException("Shopify access token is not configured")
        }

        log.info("Updating price for Shopify variant {} to {}", externalVariantId, newPrice)

        shopifyRestClient.put()
            .uri("/admin/api/2024-01/variants/{variantId}.json", externalVariantId)
            .header("X-Shopify-Access-Token", accessToken)
            .body(mapOf("variant" to mapOf("price" to newPrice.normalizedAmount.toPlainString())))
            .retrieve()
            .toBodilessEntity()

        log.info("Successfully updated price for Shopify variant {}", externalVariantId)
    }

    override fun getFees(productCategory: String, price: Money): PlatformFees {
        val transactionFee = Money.of(
            price.normalizedAmount.multiply(BigDecimal("0.020")),
            price.currency
        )
        return PlatformFees(
            transactionFee = transactionFee,
            listingFee = Money.of(BigDecimal.ZERO, price.currency)
        )
    }
}
