package com.autoshipper.portfolio.domain

import java.math.BigDecimal
import java.util.UUID

data class PriorityRanking(
    val skuId: UUID,
    val avgNetMargin: BigDecimal,
    val revenueVolume: BigDecimal,
    val riskFactor: BigDecimal,
    val riskAdjustedReturn: BigDecimal
)
