package com.autoshipper.catalog.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CostEnvelopeRepository : JpaRepository<CostEnvelopeEntity, UUID> {
    fun findBySkuId(skuId: UUID): CostEnvelopeEntity?
}
