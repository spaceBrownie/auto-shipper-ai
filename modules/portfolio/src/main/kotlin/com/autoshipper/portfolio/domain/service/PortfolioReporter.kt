package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.PortfolioConfig
import com.autoshipper.portfolio.domain.ExperimentStatus
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@Service
class PortfolioReporter(
    private val experimentService: ExperimentService,
    private val marginSignalProvider: MarginSignalProvider,
    portfolioConfig: PortfolioConfig
) {
    private val logger = LoggerFactory.getLogger(PortfolioReporter::class.java)

    data class PortfolioSummary(
        val totalExperiments: Long,
        val activeExperiments: Long,
        val activeSkus: Int,
        val terminatedSkus: Long,
        val blendedNetMargin: BigDecimal,
        val totalProfit: BigDecimal
    )

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(portfolioConfig.kpiCacheTtlMinutes, TimeUnit.MINUTES)
        .maximumSize(1)
        .build<String, PortfolioSummary>()

    fun summary(): PortfolioSummary {
        return cache.get("portfolio_summary") { computeSummary() }
    }

    private fun computeSummary(): PortfolioSummary {
        logger.info("Computing portfolio summary (cache miss)")

        val totalExperiments = experimentService.countByStatus(ExperimentStatus.ACTIVE) +
            experimentService.countByStatus(ExperimentStatus.VALIDATED) +
            experimentService.countByStatus(ExperimentStatus.FAILED) +
            experimentService.countByStatus(ExperimentStatus.LAUNCHED) +
            experimentService.countByStatus(ExperimentStatus.TERMINATED)

        val activeExperiments = experimentService.countByStatus(ExperimentStatus.ACTIVE)
        val terminatedExperiments = experimentService.countByStatus(ExperimentStatus.TERMINATED)

        val activeSkuIds = marginSignalProvider.getActiveSkuIds()
        val blendedNetMargin = marginSignalProvider.getBlendedNetMargin()
        val totalProfit = marginSignalProvider.getTotalProfit()

        return PortfolioSummary(
            totalExperiments = totalExperiments,
            activeExperiments = activeExperiments,
            activeSkus = activeSkuIds.size,
            terminatedSkus = terminatedExperiments,
            blendedNetMargin = blendedNetMargin,
            totalProfit = totalProfit
        )
    }
}
