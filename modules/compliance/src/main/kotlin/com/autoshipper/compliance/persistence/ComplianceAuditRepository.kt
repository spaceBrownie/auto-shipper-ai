package com.autoshipper.compliance.persistence

import com.autoshipper.compliance.domain.ComplianceAuditRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComplianceAuditRepository : JpaRepository<ComplianceAuditRecord, UUID> {
    fun findBySkuIdOrderByCheckedAtDesc(skuId: UUID): List<ComplianceAuditRecord>
}
