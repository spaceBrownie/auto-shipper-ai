package com.autoshipper.capital.persistence

import com.autoshipper.capital.domain.MarginSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface MarginSnapshotRepository : JpaRepository<MarginSnapshot, UUID> {

    fun findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
        skuId: UUID,
        from: LocalDate,
        to: LocalDate
    ): List<MarginSnapshot>

    fun findBySkuIdOrderBySnapshotDateDesc(skuId: UUID): List<MarginSnapshot>

    @Query(
        "SELECT m FROM MarginSnapshot m WHERE m.skuId = :skuId AND m.snapshotDate >= :since ORDER BY m.snapshotDate DESC"
    )
    fun findRecentBySkuId(
        @Param("skuId") skuId: UUID,
        @Param("since") since: LocalDate
    ): List<MarginSnapshot>
}
