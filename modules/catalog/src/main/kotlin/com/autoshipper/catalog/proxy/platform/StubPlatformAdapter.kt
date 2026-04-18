package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Component
@Profile("local")
class StubPlatformAdapter : PlatformAdapter {
    private val log = LoggerFactory.getLogger(StubPlatformAdapter::class.java)

    override fun listSku(sku: LaunchReadySku, price: Money): PlatformListingResult {
        val externalListingId = UUID.nameUUIDFromBytes(
            "product-${sku.sku.skuId()}".toByteArray()
        ).toString()
        val externalVariantId = UUID.nameUUIDFromBytes(
            "variant-${sku.sku.skuId()}".toByteArray()
        ).toString()

        log.info("[STUB] Would create Shopify listing for SKU {} at price {}: productId={}, variantId={}",
            sku.sku.skuId(), price, externalListingId, externalVariantId)

        return PlatformListingResult(
            externalListingId = externalListingId,
            externalVariantId = externalVariantId,
            inventoryItemId = null
        )
    }

    override fun pauseSku(externalListingId: String) {
        log.info("[STUB] Would pause Shopify product {}", externalListingId)
    }

    override fun archiveSku(externalListingId: String) {
        log.info("[STUB] Would archive Shopify product {}", externalListingId)
    }

    override fun updatePrice(externalVariantId: String, newPrice: Money) {
        log.info("[STUB] Would update price for Shopify variant {} to {}", externalVariantId, newPrice)
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
