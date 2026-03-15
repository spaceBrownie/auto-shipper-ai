package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceCheckType
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SourcingCheckService(
    @PersistenceContext private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(SourcingCheckService::class.java)

    fun check(skuId: SkuId, vendorId: VendorId): ComplianceCheckResult {
        val vendorStatus = queryVendorStatus(vendorId)

        if (vendorStatus == null) {
            return ComplianceCheckResult.Failed(
                skuId = skuId,
                checkType = ComplianceCheckType.SOURCING_CHECK,
                reason = ComplianceFailureReason.GRAY_MARKET_SOURCE,
                detail = "Vendor ${vendorId.value} not found in vendor registry"
            )
        }

        if (vendorStatus != "ACTIVE") {
            return ComplianceCheckResult.Failed(
                skuId = skuId,
                checkType = ComplianceCheckType.SOURCING_CHECK,
                reason = ComplianceFailureReason.GRAY_MARKET_SOURCE,
                detail = "Vendor ${vendorId.value} is not active (status: $vendorStatus)"
            )
        }

        return ComplianceCheckResult.Cleared(skuId = skuId, checkType = ComplianceCheckType.SOURCING_CHECK)
    }

    private fun queryVendorStatus(vendorId: VendorId): String? {
        @Suppress("UNCHECKED_CAST")
        val results = entityManager.createNativeQuery(
            "SELECT status FROM vendors WHERE id = :vendorId"
        ).setParameter("vendorId", vendorId.value).resultList as List<String>

        return results.firstOrNull()
    }
}
