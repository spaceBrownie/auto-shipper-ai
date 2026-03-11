package com.autoshipper.capital.domain.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class MarginSweepJob(
    private val skuProcessor: MarginSweepSkuProcessor,
    private val skuProvider: ActiveSkuProvider
) {
    private val logger = LoggerFactory.getLogger(MarginSweepJob::class.java)

    @Scheduled(fixedRate = 21_600_000) // 6 hours
    fun sweep() {
        sweep(LocalDate.now())
    }

    fun sweep(today: LocalDate) {
        val activeSkuIds = skuProvider.getActiveSkuIds()
        logger.info("Margin sweep started for {} active SKUs", activeSkuIds.size)

        for (skuId in activeSkuIds) {
            try {
                skuProcessor.process(skuId, today)
            } catch (e: Exception) {
                logger.error("Failed to sweep SKU {}", skuId, e)
            }
        }

        logger.info("Margin sweep complete")
    }
}
