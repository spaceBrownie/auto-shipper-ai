package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Percentage

class StressTestFailedException(
    skuId: SkuId,
    grossMargin: Percentage,
    netMargin: Percentage
) : RuntimeException(
    "Stress test failed for SKU $skuId: grossMargin=${grossMargin}, netMargin=${netMargin}. " +
        "Required gross >= 50%, net >= 30%."
)
