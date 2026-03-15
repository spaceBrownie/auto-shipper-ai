package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceCheckType
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class IpCheckService {

    companion object {
        private val TRADEMARKED_TERMS = setOf(
            "nike", "adidas", "apple", "samsung", "gucci", "prada", "louis vuitton",
            "chanel", "rolex", "supreme", "disney", "marvel", "nintendo", "playstation",
            "xbox", "pokemon", "lego", "barbie", "coca-cola", "pepsi", "starbucks",
            "mcdonalds", "google", "microsoft", "amazon", "tesla", "ferrari", "lamborghini",
            "hermes", "burberry", "dior", "versace", "cartier", "tiffany"
        )
    }

    fun check(skuId: SkuId, productName: String): ComplianceCheckResult {
        val nameLower = productName.lowercase()
        val matched = TRADEMARKED_TERMS.firstOrNull { nameLower.contains(it) }

        return if (matched != null) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                checkType = ComplianceCheckType.IP_CHECK,
                reason = ComplianceFailureReason.IP_INFRINGEMENT,
                detail = "Product name contains trademarked term: '$matched'"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId, checkType = ComplianceCheckType.IP_CHECK)
        }
    }
}
