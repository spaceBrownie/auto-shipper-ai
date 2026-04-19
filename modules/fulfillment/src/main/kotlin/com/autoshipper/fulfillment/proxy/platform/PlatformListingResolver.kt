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

    /**
     * Resolves the Shopify `inventory_item_id` (persisted via ShopifyListingAdapter after
     * product creation) for the given internal SKU UUID.
     *
     * Returns null when no Shopify row exists for the SKU or when the row has a null
     * `shopify_inventory_item_id` (pre-V23 data or non-Shopify platforms).
     *
     * Tiebreaker: if multiple Shopify rows exist for the same SKU (not expected in
     * practice — idempotency guard in PlatformListingListener prevents this — but
     * T-22 demands determinism), the most recently created row wins.
     */
    fun resolveInventoryItemId(skuId: UUID): String? {
        val results = entityManager.createNativeQuery(
            """SELECT shopify_inventory_item_id FROM platform_listings
               WHERE sku_id = :skuId
               AND platform = :platform
               AND shopify_inventory_item_id IS NOT NULL
               ORDER BY created_at DESC
               LIMIT 1"""
        ).setParameter("skuId", skuId)
            .setParameter("platform", "SHOPIFY")
            .resultList

        return if (results.isNotEmpty()) results.first() as String else null
    }
}
