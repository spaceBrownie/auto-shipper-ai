package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class ClaimsCheckService {

    companion object {
        val REGULATED_PATTERNS = listOf(
            "cures" to "Medical claim: 'cures'",
            "treats" to "Medical claim: 'treats'",
            "prevents disease" to "Medical claim: 'prevents disease'",
            "clinically proven" to "Unsubstantiated claim: 'clinically proven'",
            "fda approved" to "Misleading regulatory claim: 'fda approved'",
            "guaranteed results" to "Unsubstantiated guarantee: 'guaranteed results'",
            "miracle" to "Superlative/misleading: 'miracle'",
            "risk.free" to "Misleading guarantee: 'risk free'",
            "100% effective" to "Unsubstantiated claim: '100% effective'",
            "no side effects" to "Misleading medical claim: 'no side effects'",
            "doctor recommended" to "Unsubstantiated endorsement: 'doctor recommended'",
            "scientifically proven" to "Unsubstantiated claim: 'scientifically proven'",
            "lose weight fast" to "Misleading health claim: 'lose weight fast'",
            "anti.aging" to "Regulated cosmetic claim: 'anti-aging'"
        )
    }

    fun check(skuId: SkuId, description: String): ComplianceCheckResult {
        val lowerDescription = description.lowercase()
        val matchedPattern = REGULATED_PATTERNS.firstOrNull { (pattern, _) ->
            Regex(pattern).containsMatchIn(lowerDescription)
        }

        return if (matchedPattern != null) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                reason = ComplianceFailureReason.MISLEADING_CLAIMS,
                detail = matchedPattern.second
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId)
        }
    }
}
