package com.autoshipper.fulfillment.proxy.supplier

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SupplierProductMappingResolver(
    @PersistenceContext private val entityManager: EntityManager
) {
    fun resolve(skuId: UUID): SupplierProductMapping? {
        val results = entityManager.createNativeQuery(
            """SELECT supplier_product_id, supplier_variant_id
               FROM supplier_product_mappings
               WHERE sku_id = :skuId AND supplier_type = 'CJ_DROPSHIPPING'"""
        ).setParameter("skuId", skuId).resultList

        if (results.isEmpty()) return null
        val row = results.first() as Array<*>
        return SupplierProductMapping(
            supplierProductId = row[0] as String,
            supplierVariantId = row[1] as String
        )
    }
}

data class SupplierProductMapping(
    val supplierProductId: String,
    val supplierVariantId: String
)
