package com.autoshipper.pricing.proxy

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money

interface PriceSyncAdapter {
    fun syncPrice(skuId: SkuId, newPrice: Money)
}
