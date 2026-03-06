package com.autoshipper.vendor.handler.dto

import com.autoshipper.vendor.domain.VendorReliabilityScore
import java.math.BigDecimal

data class VendorScoreResponse(
    val overallScore: BigDecimal,
    val onTimeRate: BigDecimal,
    val defectRate: BigDecimal,
    val breachCount: Int,
    val avgResponseTimeHours: BigDecimal
) {
    companion object {
        fun from(score: VendorReliabilityScore) = VendorScoreResponse(
            overallScore = score.overallScore,
            onTimeRate = score.onTimeRate,
            defectRate = score.defectRate,
            breachCount = score.breachCount,
            avgResponseTimeHours = score.avgResponseTimeHours
        )
    }
}
