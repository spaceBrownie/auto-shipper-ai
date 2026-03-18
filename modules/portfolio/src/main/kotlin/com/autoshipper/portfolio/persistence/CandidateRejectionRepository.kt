package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.CandidateRejection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CandidateRejectionRepository : JpaRepository<CandidateRejection, UUID> {

    fun findByScanRunId(scanRunId: UUID): List<CandidateRejection>
}
