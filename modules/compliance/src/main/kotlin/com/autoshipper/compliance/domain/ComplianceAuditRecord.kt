package com.autoshipper.compliance.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "compliance_audit")
class ComplianceAuditRecord(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "run_id", nullable = false)
    val runId: UUID,

    @Column(name = "check_type", nullable = false)
    val checkType: String,

    @Column(name = "result", nullable = false)
    val result: String,

    @Column(name = "reason")
    val reason: String? = null,

    @Column(name = "detail")
    val detail: String? = null,

    @Column(name = "checked_at", nullable = false)
    val checkedAt: Instant = Instant.now()
)
