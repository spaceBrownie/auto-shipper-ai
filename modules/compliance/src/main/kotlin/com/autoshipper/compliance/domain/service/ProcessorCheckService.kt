package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class ProcessorCheckService {

    companion object {
        /**
         * Stripe prohibited business categories.
         * See: https://stripe.com/legal/restricted-businesses
         */
        val PROHIBITED_CATEGORIES = setOf(
            "adult_content",
            "cannabis",
            "counterfeit_goods",
            "drugs",
            "gambling",
            "firearms",
            "weapons",
            "tobacco",
            "cryptocurrency",
            "money_laundering",
            "ponzi_scheme",
            "pyramid_scheme",
            "debt_collection",
            "credit_repair",
            "telemarketing",
            "pharmaceuticals_no_prescription",
            "pseudo_pharmaceuticals",
            "stolen_goods",
            "illegal_services",
            "hate_materials"
        )
    }

    fun check(skuId: SkuId, category: String): ComplianceCheckResult {
        val normalizedCategory = category.lowercase().replace(" ", "_").replace("-", "_")

        return if (PROHIBITED_CATEGORIES.contains(normalizedCategory)) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                reason = ComplianceFailureReason.PROCESSOR_PROHIBITED,
                detail = "Category '$category' is prohibited by payment processor (Stripe)"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId)
        }
    }
}
