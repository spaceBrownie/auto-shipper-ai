package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import java.math.BigDecimal
import java.time.Instant

data class StressTestResult(
    val skuId: SkuId,
    val stressedShippingCost: Money,
    val stressedCacCost: Money,
    val stressedSupplierCost: Money,
    val stressedRefundAllowance: Money,
    val stressedChargebackAllowance: Money,
    val stressedTotalCost: Money,
    val estimatedPrice: Money,
    val grossMarginPercent: Percentage,
    val netMarginPercent: Percentage,
    val passed: Boolean,
    val shippingMultiplierUsed: BigDecimal,
    val cacIncreasePercentUsed: BigDecimal,
    val supplierIncreasePercentUsed: BigDecimal,
    val refundRatePercentUsed: BigDecimal,
    val chargebackRatePercentUsed: BigDecimal,
    val testedAt: Instant
)
