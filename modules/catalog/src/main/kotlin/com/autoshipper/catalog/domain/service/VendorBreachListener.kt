package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.shared.events.VendorSlaBreached
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class VendorBreachListener(
    private val skuService: SkuService
) {
    private val logger = LoggerFactory.getLogger(VendorBreachListener::class.java)

    @EventListener
    fun onVendorSlaBreached(event: VendorSlaBreached) {
        logger.warn(
            "Vendor SLA breached for vendor {}, affecting {} SKUs. Breach rate: {}",
            event.vendorId, event.skuIds.size, event.breachRate
        )

        for (skuId in event.skuIds) {
            try {
                val sku = skuService.findById(skuId)
                val currentState = sku.currentState()

                if (currentState == SkuState.Listed || currentState == SkuState.Scaled) {
                    skuService.transition(skuId, SkuState.Paused)
                    logger.info("Auto-paused SKU {} due to vendor {} SLA breach", skuId, event.vendorId)
                } else {
                    logger.info("SKU {} in state {} — not pausable, skipping", skuId, currentState.toDiscriminator())
                }
            } catch (e: Exception) {
                logger.error("Failed to auto-pause SKU {} for vendor breach {}", skuId, event.vendorId, e)
            }
        }
    }
}
