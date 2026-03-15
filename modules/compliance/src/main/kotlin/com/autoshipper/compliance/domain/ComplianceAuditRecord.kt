package com.autoshipper.compliance.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "compliance_audit")
class ComplianceAuditRecord(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "sku_id", nullable = false) val skuId: UUID,
    @Column(name = "check_type", nullable = false, length = 30) val checkType: String,
    @Column(name = "result", nullable = false, length = 10) val result: String,
    @Column(name = "failure_reason", length = 50) val failureReason: String? = null,
    @Column(name = "detail") val detail: String? = null,
    @Column(name = "checked_at", nullable = false, updatable = false) val checkedAt: Instant = Instant.now()
) {
    companion object {
        fun from(checkResult: ComplianceCheckResult): ComplianceAuditRecord = when (checkResult) {
            is ComplianceCheckResult.Cleared -> ComplianceAuditRecord(
                skuId = checkResult.skuId.value,
                checkType = checkResult.checkType.name,
                result = "CLEARED",
                checkedAt = checkResult.checkedAt
            )
            is ComplianceCheckResult.Failed -> ComplianceAuditRecord(
                skuId = checkResult.skuId.value,
                checkType = checkResult.checkType.name,
                result = "FAILED",
                failureReason = checkResult.reason.name,
                detail = checkResult.detail,
                checkedAt = checkResult.checkedAt
            )
        }
    }
}
