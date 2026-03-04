package com.autoshipper.pricing.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SkuPricingHistoryRepository : JpaRepository<SkuPricingHistoryEntity, Long> {
    fun findBySkuIdOrderByRecordedAtDesc(skuId: UUID): List<SkuPricingHistoryEntity>
}
