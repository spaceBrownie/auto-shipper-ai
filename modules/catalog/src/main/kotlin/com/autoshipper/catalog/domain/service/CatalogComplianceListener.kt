package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.TerminationReason
import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class CatalogComplianceListener(
    private val skuService: SkuService
) {
    private val logger = LoggerFactory.getLogger(CatalogComplianceListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onComplianceCleared(event: ComplianceCleared) {
        logger.info("Compliance cleared for SKU {}, transitioning to ValidationPending", event.skuId)
        try {
            skuService.transition(event.skuId, SkuState.ValidationPending)
        } catch (e: Exception) {
            logger.error("Failed to transition SKU {} to ValidationPending after compliance cleared", event.skuId, e)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onComplianceFailed(event: ComplianceFailed) {
        logger.warn(
            "Compliance failed for SKU {}: reason={}, terminating with COMPLIANCE_VIOLATION",
            event.skuId, event.reason
        )
        try {
            skuService.transition(event.skuId, SkuState.Terminated(TerminationReason.COMPLIANCE_VIOLATION))
        } catch (e: Exception) {
            logger.error("Failed to terminate SKU {} after compliance failure", event.skuId, e)
        }
    }
}
