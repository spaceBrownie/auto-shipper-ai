package com.autoshipper.catalog.handler.dto

import java.math.BigDecimal

data class StressTestResponse(
    val skuId: String,
    val passed: Boolean,
    val grossMarginPercent: BigDecimal,
    val netMarginPercent: BigDecimal,
    val stressedTotalCost: BigDecimal,
    val estimatedPrice: BigDecimal,
    val stressedShipping: BigDecimal,
    val stressedCac: BigDecimal,
    val stressedSupplier: BigDecimal,
    val stressedRefund: BigDecimal,
    val stressedChargeback: BigDecimal,
    val currency: String
)
