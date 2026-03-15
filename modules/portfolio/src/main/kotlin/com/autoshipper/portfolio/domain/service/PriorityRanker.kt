package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.PriorityRanking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class PriorityRanker(
    private val marginSignalProvider: MarginSignalProvider
) {
    private val logger = LoggerFactory.getLogger(PriorityRanker::class.java)

    /**
     * Scores all active SKUs by risk-adjusted return:
     * score = (avgNetMargin * revenueVolume) / riskFactor
     *
     * Risk factor is derived from refund rate — higher refund rate = higher risk.
     * Base risk = 1.0, increased by refund rate percentage.
     */
    fun rank(): List<PriorityRanking> {
        val activeSkuIds = marginSignalProvider.getActiveSkuIds()
        logger.info("PriorityRanker scoring {} active SKUs", activeSkuIds.size)

        return activeSkuIds.map { skuId ->
            val avgNetMargin = marginSignalProvider.getAverageNetMargin(skuId)
            val revenueVolume = marginSignalProvider.getAverageRevenueVolume(skuId)
            val avgRefundRate = marginSignalProvider.getAverageRefundRate(skuId)

            // Risk factor: base 1.0 + refund rate / 100
            val riskFactor = BigDecimal.ONE.add(
                avgRefundRate.divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
            )

            val riskAdjustedReturn = if (riskFactor.compareTo(BigDecimal.ZERO) != 0) {
                avgNetMargin.multiply(revenueVolume)
                    .divide(riskFactor, 4, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            PriorityRanking(
                skuId = skuId,
                avgNetMargin = avgNetMargin,
                revenueVolume = revenueVolume,
                riskFactor = riskFactor,
                riskAdjustedReturn = riskAdjustedReturn
            )
        }.sortedByDescending { it.riskAdjustedReturn }
    }
}
