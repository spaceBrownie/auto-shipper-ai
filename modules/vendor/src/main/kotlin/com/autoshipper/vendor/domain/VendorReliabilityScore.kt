package com.autoshipper.vendor.domain

import java.math.BigDecimal

data class VendorReliabilityScore(
    val overallScore: BigDecimal,
    val onTimeRate: BigDecimal,
    val defectRate: BigDecimal,
    val breachCount: Int,
    val avgResponseTimeHours: BigDecimal
)
