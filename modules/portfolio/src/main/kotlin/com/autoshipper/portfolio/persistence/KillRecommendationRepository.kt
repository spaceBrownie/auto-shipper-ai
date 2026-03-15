package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.KillRecommendation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KillRecommendationRepository : JpaRepository<KillRecommendation, UUID> {
    fun findByConfirmedAtIsNull(): List<KillRecommendation>
    fun findBySkuId(skuId: UUID): List<KillRecommendation>
}
