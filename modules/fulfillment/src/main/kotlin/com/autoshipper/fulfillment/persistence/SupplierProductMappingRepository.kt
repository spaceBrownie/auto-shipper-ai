package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.SupplierProductMapping
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SupplierProductMappingRepository : JpaRepository<SupplierProductMapping, UUID> {
    fun findBySkuId(skuId: UUID): SupplierProductMapping?
    fun findBySkuIdAndSupplier(skuId: UUID, supplier: String): SupplierProductMapping?
}
