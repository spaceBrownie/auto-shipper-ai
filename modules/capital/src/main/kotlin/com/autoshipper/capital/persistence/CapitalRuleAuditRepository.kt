package com.autoshipper.capital.persistence

import com.autoshipper.capital.domain.CapitalRuleAudit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CapitalRuleAuditRepository : JpaRepository<CapitalRuleAudit, UUID>
