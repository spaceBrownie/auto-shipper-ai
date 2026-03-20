package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.shared.money.Money

interface PlatformAdapter {
    fun listSku(sku: LaunchReadySku, price: Money): PlatformListingResult
    fun pauseSku(externalListingId: String)
    fun archiveSku(externalListingId: String)
    fun updatePrice(externalVariantId: String, newPrice: Money)
    fun getFees(productCategory: String, price: Money): PlatformFees
}

data class PlatformListingResult(
    val externalListingId: String,
    val externalVariantId: String?
)
