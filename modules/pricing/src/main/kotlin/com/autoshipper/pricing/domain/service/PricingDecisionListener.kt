package com.autoshipper.pricing.domain.service

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.TerminationReason
import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.pricing.proxy.ShopifyPriceSyncAdapter
import com.autoshipper.shared.events.PricingDecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class PricingDecisionListener(
    private val skuService: SkuService,
    private val shopifyPriceSyncAdapter: ShopifyPriceSyncAdapter
) {
    private val log = LoggerFactory.getLogger(PricingDecisionListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onPricingDecision(decision: PricingDecision) {
        when (decision) {
            is PricingDecision.Adjusted -> {
                log.info("Price adjusted for SKU {} to {}", decision.skuId, decision.newPrice)
                shopifyPriceSyncAdapter.syncPrice(decision.skuId, decision.newPrice)
            }

            is PricingDecision.PauseRequired -> {
                log.warn("Pausing SKU {} — {}", decision.skuId, decision.reason)
                skuService.transition(decision.skuId, SkuState.Paused)
            }

            is PricingDecision.TerminateRequired -> {
                log.warn("Terminating SKU {} — {}", decision.skuId, decision.reason)
                skuService.transition(
                    decision.skuId,
                    SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR)
                )
            }
        }
    }
}
