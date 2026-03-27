package com.autoshipper.fulfillment.proxy.platform

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves the supplier-specific variant ID for a given SKU and supplier.
 * Reads from the supplier_product_mappings table.
 */
@Component
class SupplierProductMappingResolver(
    @PersistenceContext private val entityManager: EntityManager
) {
    fun resolveSupplierVariantId(skuId: UUID, supplier: String): String? {
        val results = entityManager.createNativeQuery(
            """SELECT supplier_variant_id FROM supplier_product_mappings
               WHERE sku_id = :skuId AND supplier = :supplier"""
        ).setParameter("skuId", skuId)
            .setParameter("supplier", supplier)
            .resultList
        return if (results.isNotEmpty()) results.first() as String else null
    }
}
