package com.autoshipper.catalog.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SkuStateHistoryRepository : JpaRepository<SkuStateHistory, Long> {

    fun findBySkuIdOrderByTransitionedAtAsc(skuId: UUID): List<SkuStateHistory>
}
