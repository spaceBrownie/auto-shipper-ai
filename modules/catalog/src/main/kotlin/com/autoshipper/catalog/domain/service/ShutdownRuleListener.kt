package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.TerminationReason
import com.autoshipper.shared.events.ShutdownRuleTriggered
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ShutdownRuleListener(
    private val skuService: SkuService
) {
    private val logger = LoggerFactory.getLogger(ShutdownRuleListener::class.java)

    @EventListener
    fun onShutdownRuleTriggered(event: ShutdownRuleTriggered) {
        logger.warn(
            "Shutdown rule triggered for SKU {}: rule={}, value={}, action={}",
            event.skuId, event.rule, event.conditionValue, event.action
        )

        try {
            val sku = skuService.findById(event.skuId)
            val currentState = sku.currentState()

            if (currentState == SkuState.Listed || currentState == SkuState.Scaled) {
                when (event.action) {
                    "PAUSE", "PAUSE_COMPLIANCE" -> {
                        skuService.transition(event.skuId, SkuState.Paused)
                        logger.info("Auto-paused SKU {} due to {}", event.skuId, event.rule)
                    }
                    "TERMINATE" -> {
                        val reason = when (event.rule) {
                            "MARGIN_BREACH" -> TerminationReason.MARGIN_BELOW_FLOOR
                            "REFUND_RATE_BREACH" -> TerminationReason.REFUND_RATE_EXCEEDED
                            "CHARGEBACK_RATE_BREACH" -> TerminationReason.CHARGEBACK_RATE_EXCEEDED
                            else -> TerminationReason.MANUAL_OVERRIDE
                        }
                        skuService.transition(event.skuId, SkuState.Terminated(reason))
                        logger.info("Auto-terminated SKU {} due to {}", event.skuId, event.rule)
                    }
                    else -> {
                        logger.warn("Unknown shutdown action '{}' for SKU {}, defaulting to pause", event.action, event.skuId)
                        skuService.transition(event.skuId, SkuState.Paused)
                    }
                }
            } else {
                logger.info(
                    "SKU {} in state {} — not pausable/terminable, skipping shutdown rule",
                    event.skuId, currentState.toDiscriminator()
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to process shutdown rule for SKU {}", event.skuId, e)
        }
    }
}
