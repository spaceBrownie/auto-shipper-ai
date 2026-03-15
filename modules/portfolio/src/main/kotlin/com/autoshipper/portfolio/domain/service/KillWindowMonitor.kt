package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.PortfolioConfig
import com.autoshipper.portfolio.domain.KillRecommendation
import com.autoshipper.portfolio.persistence.KillRecommendationRepository
import com.autoshipper.shared.events.KillWindowBreached
import com.autoshipper.shared.identity.SkuId
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class KillWindowMonitor(
    private val portfolioConfig: PortfolioConfig,
    private val marginSignalProvider: MarginSignalProvider,
    private val killRecommendationRepository: KillRecommendationRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(KillWindowMonitor::class.java)

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    fun scan() {
        val killWindowDays = portfolioConfig.killWindowDays
        logger.info("KillWindowMonitor scan started (killWindowDays={})", killWindowDays)

        val qualifyingSkuIds = marginSignalProvider.getSkusWithNegativeMarginSince(killWindowDays)

        for (skuId in qualifyingSkuIds) {
            val avgNetMargin = marginSignalProvider.getAverageNetMargin(skuId)

            val recommendation = KillRecommendation(
                skuId = skuId,
                daysNegative = killWindowDays,
                avgNetMargin = avgNetMargin
            )
            killRecommendationRepository.save(recommendation)
            logger.warn(
                "Kill recommendation created for SKU {} (daysNegative={}, avgNetMargin={})",
                skuId, killWindowDays, avgNetMargin
            )

            if (portfolioConfig.autoTerminateEnabled) {
                val event = KillWindowBreached(
                    skuId = SkuId(skuId),
                    daysNegative = killWindowDays,
                    avgNetMargin = avgNetMargin
                )
                eventPublisher.publishEvent(event)
                logger.warn("KillWindowBreached event published for SKU {}", skuId)
            }
        }

        logger.info("KillWindowMonitor scan completed: {} SKUs flagged", qualifyingSkuIds.size)
    }
}
