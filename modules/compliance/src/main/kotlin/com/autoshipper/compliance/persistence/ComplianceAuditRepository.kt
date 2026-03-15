package com.autoshipper.compliance.persistence

import com.autoshipper.compliance.domain.ComplianceAuditRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComplianceAuditRepository : JpaRepository<ComplianceAuditRecord, UUID> {
    fun findBySkuIdOrderByCheckedAtDesc(skuId: UUID): List<ComplianceAuditRecord>

    @Query("SELECT DISTINCT r.runId FROM ComplianceAuditRecord r WHERE r.skuId = :skuId ORDER BY r.runId DESC")
    fun findDistinctRunIdsBySkuId(skuId: UUID): List<UUID>

    fun findBySkuIdAndRunId(skuId: UUID, runId: UUID): List<ComplianceAuditRecord>
}
