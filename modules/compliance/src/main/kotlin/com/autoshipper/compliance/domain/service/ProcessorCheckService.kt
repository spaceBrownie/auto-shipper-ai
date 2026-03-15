package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceCheckType
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class ProcessorCheckService {

    companion object {
        private val STRIPE_PROHIBITED_CATEGORIES = setOf(
            "drugs", "drug paraphernalia", "tobacco", "firearms", "weapons",
            "ammunition", "explosives", "counterfeit goods", "gambling",
            "adult content", "pornography", "cryptocurrency", "virtual currency",
            "money laundering", "pyramid schemes", "multi-level marketing",
            "debt collection", "bail bonds", "escort services",
            "unlicensed pharmaceuticals", "steroids", "controlled substances"
        )
    }

    fun check(skuId: SkuId, category: String): ComplianceCheckResult {
        val categoryLower = category.lowercase()
        val prohibited = STRIPE_PROHIBITED_CATEGORIES.firstOrNull { categoryLower.contains(it) }

        return if (prohibited != null) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                checkType = ComplianceCheckType.PROCESSOR_CHECK,
                reason = ComplianceFailureReason.PROCESSOR_PROHIBITED,
                detail = "Category matches Stripe prohibited category: '$prohibited'"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId, checkType = ComplianceCheckType.PROCESSOR_CHECK)
        }
    }
}
