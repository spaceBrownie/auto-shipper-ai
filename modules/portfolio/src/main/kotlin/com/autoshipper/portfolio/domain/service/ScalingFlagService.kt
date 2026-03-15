package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.ScalingFlag
import com.autoshipper.portfolio.persistence.ScalingFlagRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class ScalingFlagService(
    private val marginSignalProvider: MarginSignalProvider,
    private val scalingFlagRepository: ScalingFlagRepository
) {
    private val logger = LoggerFactory.getLogger(ScalingFlagService::class.java)

    companion object {
        private val SCALING_MARGIN_THRESHOLD = BigDecimal("50")
        private const val CONSECUTIVE_SNAPSHOTS_REQUIRED = 3
    }

    @Transactional
    fun scan() {
        val activeSkuIds = marginSignalProvider.getActiveSkuIds()
        logger.info("ScalingFlagService scan started for {} active SKUs", activeSkuIds.size)

        for (skuId in activeSkuIds) {
            val existingFlag = scalingFlagRepository.findBySkuIdAndResolvedAtIsNull(skuId)
            if (existingFlag != null) {
                continue // Already flagged
            }

            val consecutiveHighDays = marginSignalProvider.getConsecutiveHighMarginDays(
                skuId, SCALING_MARGIN_THRESHOLD
            )

            if (consecutiveHighDays >= CONSECUTIVE_SNAPSHOTS_REQUIRED) {
                val flag = ScalingFlag(skuId = skuId)
                scalingFlagRepository.save(flag)
                logger.info(
                    "Scaling flag created for SKU {} ({} consecutive high-margin snapshots)",
                    skuId, consecutiveHighDays
                )
            }
        }
    }
}
