package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.TerminationReason
import com.autoshipper.shared.events.KillWindowBreached
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class CatalogKillWindowListener(
    private val skuService: SkuService
) {
    private val logger = LoggerFactory.getLogger(CatalogKillWindowListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onKillWindowBreached(event: KillWindowBreached) {
        logger.warn(
            "Kill window breached for SKU {}: daysNegative={}, avgNetMargin={}",
            event.skuId, event.daysNegative, event.avgNetMargin
        )

        try {
            val sku = skuService.findById(event.skuId)
            val currentState = sku.currentState()

            if (currentState == SkuState.Listed || currentState == SkuState.Scaled || currentState == SkuState.Paused) {
                skuService.transition(event.skuId, SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR))
                logger.info("Auto-terminated SKU {} due to kill window breach", event.skuId)
            } else {
                logger.info(
                    "SKU {} in state {} — not terminable, skipping kill window action",
                    event.skuId, currentState.toDiscriminator()
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to process kill window breach for SKU {}", event.skuId, e)
        }
    }
}
