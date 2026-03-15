package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.config.ComplianceConfig
import com.autoshipper.compliance.domain.ComplianceAuditRecord
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import com.autoshipper.shared.events.SkuReadyForComplianceCheck
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class ComplianceOrchestrator(
    private val ipCheckService: IpCheckService,
    private val claimsCheckService: ClaimsCheckService,
    private val processorCheckService: ProcessorCheckService,
    private val sourcingCheckService: SourcingCheckService,
    private val auditRepository: ComplianceAuditRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val config: ComplianceConfig
) {
    private val logger = LoggerFactory.getLogger(ComplianceOrchestrator::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSkuReadyForComplianceCheck(event: SkuReadyForComplianceCheck) {
        if (!config.autoCheckEnabled) {
            logger.debug("Auto-check disabled, ignoring SkuReadyForComplianceCheck for SKU {}", event.skuId)
            return
        }

        logger.info("Auto-check triggered for SKU {}", event.skuId)
        runChecks(
            skuId = event.skuId,
            productName = event.productName,
            productDescription = event.productDescription,
            category = event.category,
            vendorId = event.vendorId
        )
    }

    @Transactional
    fun runChecks(
        skuId: SkuId,
        productName: String,
        productDescription: String,
        category: String,
        vendorId: VendorId
    ) {
        logger.info("Running compliance checks for SKU {}", skuId)

        val results = runBlocking {
            runChecksConcurrently(skuId, productName, productDescription, category, vendorId)
        }

        // Write audit records for all checks
        writeAuditRecords(skuId, results)

        // Determine overall result
        val firstFailure = results.firstOrNull { it.second is ComplianceCheckResult.Failed }

        if (firstFailure != null) {
            val failed = firstFailure.second as ComplianceCheckResult.Failed
            logger.warn("Compliance check FAILED for SKU {}: {} — {}", skuId, failed.reason, failed.detail)
            eventPublisher.publishEvent(
                ComplianceFailed(skuId = skuId, reason = failed.reason.name)
            )
        } else {
            logger.info("All compliance checks PASSED for SKU {}", skuId)
            eventPublisher.publishEvent(
                ComplianceCleared(skuId = skuId)
            )
        }
    }

    internal suspend fun runChecksConcurrently(
        skuId: SkuId,
        productName: String,
        productDescription: String,
        category: String,
        vendorId: VendorId
    ): List<Pair<String, ComplianceCheckResult>> = coroutineScope {
        val ipCheck = async { "IP_CHECK" to ipCheckService.check(skuId, productName) }
        val claimsCheck = async { "CLAIMS_CHECK" to claimsCheckService.check(skuId, productDescription) }
        val processorCheck = async { "PROCESSOR_CHECK" to processorCheckService.check(skuId, category) }
        val sourcingCheck = async { "SOURCING_CHECK" to sourcingCheckService.check(skuId, vendorId) }

        listOf(ipCheck.await(), claimsCheck.await(), processorCheck.await(), sourcingCheck.await())
    }

    private fun writeAuditRecords(skuId: SkuId, results: List<Pair<String, ComplianceCheckResult>>) {
        results.forEach { (checkType, result) ->
            val record = when (result) {
                is ComplianceCheckResult.Cleared -> ComplianceAuditRecord(
                    skuId = skuId.value,
                    checkType = checkType,
                    result = "CLEARED"
                )
                is ComplianceCheckResult.Failed -> ComplianceAuditRecord(
                    skuId = skuId.value,
                    checkType = checkType,
                    result = "FAILED",
                    reason = result.reason.name,
                    detail = result.detail
                )
            }
            auditRepository.save(record)
        }
    }
}
