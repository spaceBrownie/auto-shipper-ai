package com.autoshipper.compliance.domain

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

sealed class ComplianceCheckResult {
    abstract val skuId: SkuId
    abstract val checkType: ComplianceCheckType
    abstract val checkedAt: Instant

    data class Cleared(
        override val skuId: SkuId,
        override val checkType: ComplianceCheckType,
        override val checkedAt: Instant = Instant.now()
    ) : ComplianceCheckResult()

    data class Failed(
        override val skuId: SkuId,
        override val checkType: ComplianceCheckType,
        val reason: ComplianceFailureReason,
        val detail: String? = null,
        override val checkedAt: Instant = Instant.now()
    ) : ComplianceCheckResult()
}

enum class ComplianceCheckType {
    IP_CHECK,
    CLAIMS_CHECK,
    PROCESSOR_CHECK,
    SOURCING_CHECK
}
