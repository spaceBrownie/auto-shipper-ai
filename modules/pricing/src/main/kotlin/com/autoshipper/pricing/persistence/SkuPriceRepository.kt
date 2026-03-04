package com.autoshipper.pricing.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SkuPriceRepository : JpaRepository<SkuPriceEntity, UUID> {
    fun findBySkuId(skuId: UUID): SkuPriceEntity?
}
