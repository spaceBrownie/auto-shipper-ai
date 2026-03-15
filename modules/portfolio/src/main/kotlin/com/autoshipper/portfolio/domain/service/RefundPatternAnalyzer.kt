package com.autoshipper.portfolio.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class RefundPatternAnalyzer(
    private val marginSignalProvider: MarginSignalProvider
) {
    private val logger = LoggerFactory.getLogger(RefundPatternAnalyzer::class.java)

    companion object {
        private val ELEVATED_REFUND_THRESHOLD = BigDecimal("3.0")
    }

    data class RefundAlert(
        val skuIds: List<java.util.UUID>,
        val portfolioAvgRefundRate: BigDecimal,
        val elevatedSkuCount: Int
    )

    /**
     * Analyzes cross-SKU refund trends. Identifies SKUs whose refund rate
     * significantly exceeds the portfolio average.
     */
    fun analyze(): RefundAlert {
        val activeSkuIds = marginSignalProvider.getActiveSkuIds()
        val portfolioAvgRefundRate = marginSignalProvider.getPortfolioAverageRefundRate()

        val elevatedSkus = activeSkuIds.filter { skuId ->
            val skuRefundRate = marginSignalProvider.getAverageRefundRate(skuId)
            skuRefundRate > ELEVATED_REFUND_THRESHOLD && skuRefundRate > portfolioAvgRefundRate
        }

        if (elevatedSkus.isNotEmpty()) {
            logger.warn(
                "Refund pattern alert: {} SKUs with elevated refund rates (portfolio avg: {}%)",
                elevatedSkus.size, portfolioAvgRefundRate
            )
        }

        return RefundAlert(
            skuIds = elevatedSkus,
            portfolioAvgRefundRate = portfolioAvgRefundRate,
            elevatedSkuCount = elevatedSkus.size
        )
    }
}
