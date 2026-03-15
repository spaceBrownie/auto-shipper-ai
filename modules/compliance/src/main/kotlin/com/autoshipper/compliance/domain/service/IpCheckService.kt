package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class IpCheckService {

    companion object {
        val TRADEMARKED_TERMS = listOf(
            "nike", "adidas", "apple", "samsung", "google", "microsoft",
            "gucci", "louis vuitton", "chanel", "prada", "rolex",
            "disney", "marvel", "pokemon", "nintendo", "sony",
            "coca-cola", "pepsi", "starbucks", "mcdonalds",
            "supreme", "off-white", "balenciaga", "hermes",
            "iphone", "ipad", "macbook", "airpods", "playstation", "xbox"
        )
    }

    fun check(skuId: SkuId, productName: String): ComplianceCheckResult {
        val lowerName = productName.lowercase()
        val matchedTerm = TRADEMARKED_TERMS.firstOrNull { term ->
            lowerName.contains(term)
        }

        return if (matchedTerm != null) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                reason = ComplianceFailureReason.IP_INFRINGEMENT,
                detail = "Product name contains trademarked term: '$matchedTerm'"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId)
        }
    }
}
