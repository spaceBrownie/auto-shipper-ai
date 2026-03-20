package com.autoshipper.catalog.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlatformListingRepository : JpaRepository<PlatformListingEntity, UUID> {
    fun findBySkuId(skuId: UUID): PlatformListingEntity?
    fun findBySkuIdAndPlatform(skuId: UUID, platform: String): PlatformListingEntity?
}
