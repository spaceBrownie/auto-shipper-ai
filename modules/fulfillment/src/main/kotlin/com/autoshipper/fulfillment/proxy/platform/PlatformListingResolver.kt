package com.autoshipper.fulfillment.proxy.platform

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves Shopify product/variant IDs to internal SKU IDs by querying the
 * platform_listings table (owned by the catalog module) via native SQL.
 *
 * Follows the JpaActiveSkuProvider / JpaOrderAmountProvider pattern from
 * the capital module — cross-module read-only access via EntityManager
 * without importing catalog module code.
 */
@Component
class PlatformListingResolver(
    @PersistenceContext private val entityManager: EntityManager
) {

    fun resolveSkuId(externalListingId: String, externalVariantId: String?, platform: String): UUID? {
        val query = if (externalVariantId != null) {
            entityManager.createNativeQuery(
                """SELECT sku_id FROM platform_listings
                   WHERE external_listing_id = :listingId
                   AND external_variant_id = :variantId
                   AND platform = :platform
                   AND status = 'ACTIVE'"""
            ).setParameter("listingId", externalListingId)
                .setParameter("variantId", externalVariantId)
                .setParameter("platform", platform)
        } else {
            entityManager.createNativeQuery(
                """SELECT sku_id FROM platform_listings
                   WHERE external_listing_id = :listingId
                   AND platform = :platform
                   AND status = 'ACTIVE'"""
            ).setParameter("listingId", externalListingId)
                .setParameter("platform", platform)
        }

        val results = query.resultList
        return if (results.isNotEmpty()) results.first() as UUID else null
    }
}
