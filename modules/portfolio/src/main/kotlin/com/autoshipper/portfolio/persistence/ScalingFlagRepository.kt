package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.ScalingFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ScalingFlagRepository : JpaRepository<ScalingFlag, UUID> {
    fun findBySkuIdAndResolvedAtIsNull(skuId: UUID): ScalingFlag?
}
