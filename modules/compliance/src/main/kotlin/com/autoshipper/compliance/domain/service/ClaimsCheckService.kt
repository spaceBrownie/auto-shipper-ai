package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceCheckType
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.springframework.stereotype.Service

@Service
class ClaimsCheckService {

    companion object {
        private val REGULATED_CLAIM_PATTERNS = listOf(
            Regex("\\bcures?\\b", RegexOption.IGNORE_CASE),
            Regex("\\btreats?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bheals?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bprevents?\\s+(cancer|disease|diabetes|covid)", RegexOption.IGNORE_CASE),
            Regex("\\bfda[- ]?approved\\b", RegexOption.IGNORE_CASE),
            Regex("\\bclinically[- ]?proven\\b", RegexOption.IGNORE_CASE),
            Regex("\\bguaranteed\\s+(results?|weight[- ]?loss|cure)", RegexOption.IGNORE_CASE),
            Regex("\\bmiracle\\b", RegexOption.IGNORE_CASE),
            Regex("\\b100%\\s+effective\\b", RegexOption.IGNORE_CASE),
            Regex("\\bno\\s+side\\s+effects?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bdoctor[- ]?recommended\\b", RegexOption.IGNORE_CASE),
            Regex("\\bmedically[- ]?certified\\b", RegexOption.IGNORE_CASE)
        )
    }

    fun check(skuId: SkuId, productDescription: String): ComplianceCheckResult {
        val matched = REGULATED_CLAIM_PATTERNS.firstOrNull { it.containsMatchIn(productDescription) }

        return if (matched != null) {
            ComplianceCheckResult.Failed(
                skuId = skuId,
                checkType = ComplianceCheckType.CLAIMS_CHECK,
                reason = ComplianceFailureReason.MISLEADING_CLAIMS,
                detail = "Description contains regulated claim: '${matched.find(productDescription)?.value}'"
            )
        } else {
            ComplianceCheckResult.Cleared(skuId = skuId, checkType = ComplianceCheckType.CLAIMS_CHECK)
        }
    }
}
