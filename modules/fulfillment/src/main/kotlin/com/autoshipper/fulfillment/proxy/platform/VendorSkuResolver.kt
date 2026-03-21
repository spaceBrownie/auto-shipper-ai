package com.autoshipper.fulfillment.proxy.platform

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves internal SKU IDs to their active vendor assignment by querying the
 * vendor_sku_assignments table (owned by the vendor module) via native SQL.
 *
 * Follows the JpaActiveSkuProvider / JpaOrderAmountProvider pattern from
 * the capital module — cross-module read-only access via EntityManager
 * without importing vendor module domain types.
 */
@Component
class VendorSkuResolver(
    @PersistenceContext private val entityManager: EntityManager
) {

    fun resolveVendorId(skuId: UUID): UUID? {
        val results = entityManager.createNativeQuery(
            """SELECT vendor_id FROM vendor_sku_assignments
               WHERE sku_id = :skuId AND active = true
               ORDER BY assigned_at DESC LIMIT 1"""
        ).setParameter("skuId", skuId).resultList

        return if (results.isNotEmpty()) results.first() as UUID else null
    }
}
