package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.config.ComplianceConfig
import com.autoshipper.compliance.domain.ComplianceAuditRecord
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import com.autoshipper.shared.events.SkuReadyForComplianceCheck
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ComplianceOrchestrator(
    private val ipCheckService: IpCheckService,
    private val claimsCheckService: ClaimsCheckService,
    private val processorCheckService: ProcessorCheckService,
    private val sourcingCheckService: SourcingCheckService,
    private val auditRepository: ComplianceAuditRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val complianceConfig: ComplianceConfig
) {
    private val logger = LoggerFactory.getLogger(ComplianceOrchestrator::class.java)

    @EventListener
    fun onSkuReadyForComplianceCheck(event: SkuReadyForComplianceCheck) {
        if (!complianceConfig.autoCheckEnabled) {
            logger.debug("Auto-check disabled, skipping compliance check for SKU {}", event.skuId)
            return
        }
        runChecks(event.skuId, event.productName, event.productDescription, event.category, event.vendorId)
    }

    @Transactional
    fun runChecks(
        skuId: SkuId,
        productName: String,
        productDescription: String,
        category: String,
        vendorId: VendorId
    ): List<ComplianceCheckResult> {
        logger.info("Running compliance checks for SKU {}", skuId)

        val results = runBlocking {
            supervisorScope {
                val ipCheck = async { ipCheckService.check(skuId, productName) }
                val claimsCheck = async { claimsCheckService.check(skuId, productDescription) }
                val processorCheck = async { processorCheckService.check(skuId, category) }
                val sourcingCheck = async { sourcingCheckService.check(skuId, vendorId) }

                listOf(ipCheck.await(), claimsCheck.await(), processorCheck.await(), sourcingCheck.await())
            }
        }

        results.forEach { result ->
            auditRepository.save(ComplianceAuditRecord.from(result))
        }

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()

        if (failures.isEmpty()) {
            logger.info("All compliance checks passed for SKU {}", skuId)
            eventPublisher.publishEvent(ComplianceCleared(skuId = skuId))
        } else {
            val primaryFailure = failures.first()
            logger.warn(
                "Compliance check failed for SKU {}: {} — {}",
                skuId, primaryFailure.reason, primaryFailure.detail
            )
            eventPublisher.publishEvent(
                ComplianceFailed(skuId = skuId, reason = primaryFailure.reason.name)
            )
        }

        return results
    }
}
