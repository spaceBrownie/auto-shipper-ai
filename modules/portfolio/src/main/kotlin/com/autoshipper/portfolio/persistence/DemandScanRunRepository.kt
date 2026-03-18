package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.DemandScanRun
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DemandScanRunRepository : JpaRepository<DemandScanRun, UUID> {

    fun findTopByOrderByStartedAtDesc(): DemandScanRun?

    fun findByStatus(status: String): List<DemandScanRun>
}
