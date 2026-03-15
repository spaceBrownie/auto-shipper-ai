package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.proxy.SanctionsListCache
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import org.springframework.stereotype.Service

@Service
class SourcingCheckService(
    private val sanctionsListCache: SanctionsListCache
) {

    fun check(skuId: SkuId, vendorId: VendorId): ComplianceCheckResult {
        val sanctionedVendors = sanctionsListCache.getSanctionedVendorIds()

        return if (sanctionedVendors.contains(vendorId.value.toString())) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                reason = ComplianceFailureReason.GRAY_MARKET_SOURCE,
                detail = "Vendor $vendorId is on the sanctions list"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId)
        }
    }
}
