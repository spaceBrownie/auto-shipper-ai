package com.autoshipper.catalog.persistence

import com.autoshipper.catalog.domain.Sku
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SkuRepository : JpaRepository<Sku, UUID> {
    fun findByCurrentStateDiscriminator(state: String): List<Sku>
}
