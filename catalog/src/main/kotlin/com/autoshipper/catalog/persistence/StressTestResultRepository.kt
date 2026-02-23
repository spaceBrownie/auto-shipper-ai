package com.autoshipper.catalog.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StressTestResultRepository : JpaRepository<StressTestResultEntity, UUID> {
    fun findBySkuId(skuId: UUID): List<StressTestResultEntity>
}
