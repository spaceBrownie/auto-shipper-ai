package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.DemandCandidate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DemandCandidateRepository : JpaRepository<DemandCandidate, UUID> {

    fun findByScanRunId(scanRunId: UUID): List<DemandCandidate>

    @Query(
        value = "SELECT * FROM demand_candidates WHERE similarity(product_name, :name) > :threshold",
        nativeQuery = true
    )
    fun findSimilarByName(
        @Param("name") name: String,
        @Param("threshold") threshold: Double
    ): List<DemandCandidate>
}
