package com.autoshipper.compliance.handler

import java.time.Instant

data class ComplianceStatusResponse(
    val skuId: String,
    val latestResult: String?,
    val latestReason: String?,
    val auditHistory: List<AuditEntry>
)

data class AuditEntry(
    val checkType: String,
    val result: String,
    val reason: String?,
    val detail: String?,
    val checkedAt: Instant
)
