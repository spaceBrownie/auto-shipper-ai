package com.autoshipper.compliance.domain

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

sealed class ComplianceCheckResult {
    data class Cleared(
        val skuId: SkuId,
        val checkedAt: Instant = Instant.now()
    ) : ComplianceCheckResult()

    data class Failed(
        val skuId: SkuId,
        val reason: ComplianceFailureReason,
        val detail: String,
        val checkedAt: Instant = Instant.now()
    ) : ComplianceCheckResult()
}
