package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.identity.VendorId
import com.autoshipper.vendor.domain.VendorReliabilityScore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

@Service
@Transactional(readOnly = true)
class VendorReliabilityScorer(
    private val fulfillmentDataProvider: VendorFulfillmentDataProvider
) {
    companion object {
        private val ON_TIME_WEIGHT = BigDecimal("0.40")
        private val DEFECT_WEIGHT = BigDecimal("0.25")
        private val BREACH_WEIGHT = BigDecimal("0.20")
        private val RESPONSE_WEIGHT = BigDecimal("0.15")
        private val HUNDRED = BigDecimal(100)
        private val MAX_BREACH_PENALTY = BigDecimal(10)
        private val MAX_RESPONSE_HOURS = BigDecimal(72)
        val ROLLING_WINDOW: Duration = Duration.ofDays(30)
    }

    fun compute(
        vendorId: VendorId,
        onTimeRate: BigDecimal,
        defectRate: BigDecimal,
        avgResponseTimeHours: BigDecimal
    ): VendorReliabilityScore {
        val since = Instant.now().minus(ROLLING_WINDOW)
        val breachCount = fulfillmentDataProvider.countViolationsSince(vendorId.value, since)

        val onTimeScore = onTimeRate.coerceIn(BigDecimal.ZERO, HUNDRED)
        val defectScore = (HUNDRED - defectRate.multiply(BigDecimal(10)))
            .coerceIn(BigDecimal.ZERO, HUNDRED)
        val breachScore = (HUNDRED - BigDecimal(breachCount).multiply(MAX_BREACH_PENALTY))
            .coerceIn(BigDecimal.ZERO, HUNDRED)
        val responseScore = (HUNDRED - avgResponseTimeHours.divide(MAX_RESPONSE_HOURS, 4, RoundingMode.HALF_UP).multiply(HUNDRED))
            .coerceIn(BigDecimal.ZERO, HUNDRED)

        val overall = onTimeScore.multiply(ON_TIME_WEIGHT)
            .add(defectScore.multiply(DEFECT_WEIGHT))
            .add(breachScore.multiply(BREACH_WEIGHT))
            .add(responseScore.multiply(RESPONSE_WEIGHT))
            .setScale(2, RoundingMode.HALF_UP)

        return VendorReliabilityScore(
            overallScore = overall,
            onTimeRate = onTimeRate,
            defectRate = defectRate,
            breachCount = breachCount.toInt(),
            avgResponseTimeHours = avgResponseTimeHours
        )
    }
}
