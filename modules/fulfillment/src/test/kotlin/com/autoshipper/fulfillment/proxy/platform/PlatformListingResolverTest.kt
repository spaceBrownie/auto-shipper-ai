package com.autoshipper.fulfillment.proxy.platform

import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PlatformListingResolverTest {

    @Mock
    lateinit var entityManager: EntityManager

    @InjectMocks
    lateinit var resolver: PlatformListingResolver

    @Test
    fun `resolveSkuId returns UUID when listing found with variant`() {
        val expectedSkuId = UUID.randomUUID()
        val query = mock<Query>()

        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("listingId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("variantId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(listOf(expectedSkuId))

        val result = resolver.resolveSkuId("7513594", "34505432", "SHOPIFY")

        assert(result == expectedSkuId) {
            "Expected SKU ID $expectedSkuId but got $result"
        }

        verify(entityManager).createNativeQuery(argThat<String> {
            contains("external_listing_id") && contains("external_variant_id") && contains("platform") && contains("ACTIVE")
        })
        verify(query).setParameter("listingId", "7513594")
        verify(query).setParameter("variantId", "34505432")
        verify(query).setParameter("platform", "SHOPIFY")
    }

    @Test
    fun `resolveSkuId returns UUID when listing found without variant`() {
        val expectedSkuId = UUID.randomUUID()
        val query = mock<Query>()

        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("listingId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(listOf(expectedSkuId))

        val result = resolver.resolveSkuId("7513594", null, "SHOPIFY")

        assert(result == expectedSkuId) {
            "Expected SKU ID $expectedSkuId but got $result"
        }

        // Verify the query does NOT contain variant_id parameter binding
        verify(query, never()).setParameter(eq("variantId"), any())
    }

    @Test
    fun `resolveSkuId returns null when no listing found`() {
        val query = mock<Query>()

        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("listingId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("variantId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        val result = resolver.resolveSkuId("unknown", "unknown", "SHOPIFY")

        assert(result == null) {
            "Expected null but got $result"
        }
    }

    // ---------------------------------------------------------------------
    // FR-030 / RAT-53 — resolveInventoryItemId (T-18..T-23)
    //
    // These tests mock the EntityManager because the resolver uses native SQL;
    // the SQL filter semantics (platform = SHOPIFY AND shopify_inventory_item_id
    // IS NOT NULL AND ORDER BY created_at DESC) are asserted via the query string
    // capture AND by modeling the query's resultList as the database engine would
    // return it (i.e. rows violating filters do not appear in resultList).
    // ---------------------------------------------------------------------

    @Test
    fun `resolveInventoryItemId T-18 returns inventory item id when row exists`() {
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(listOf("item_abc"))

        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == "item_abc") { "Expected 'item_abc' but got $result" }
        verify(query).setParameter("skuId", skuId)
        verify(query).setParameter("platform", "SHOPIFY")
    }

    @Test
    fun `resolveInventoryItemId T-19 returns null when no rows for skuId`() {
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == null) { "Expected null but got $result" }
    }

    @Test
    fun `resolveInventoryItemId T-20 returns null when row has null inventory item id (filtered in SQL)`() {
        // The SQL filters `shopify_inventory_item_id IS NOT NULL`, so a NULL-only
        // row never appears in resultList. Modeled as empty resultList.
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == null) { "Expected null for row with NULL inventory_item_id but got $result" }
        // Assert the SQL explicitly filters out NULL — this is load-bearing
        verify(entityManager).createNativeQuery(argThat<String> {
            contains("shopify_inventory_item_id IS NOT NULL")
        })
    }

    @Test
    fun `resolveInventoryItemId T-21 filters by platform SHOPIFY`() {
        // If a non-shopify row exists, the platform filter excludes it → empty result.
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == null) { "Expected null when only non-shopify rows exist but got $result" }
        verify(entityManager).createNativeQuery(argThat<String> {
            contains("platform = :platform")
        })
        verify(query).setParameter("platform", "SHOPIFY")
    }

    @Test
    fun `resolveInventoryItemId T-22 returns most recently created row when multiple rows exist`() {
        // SQL uses `ORDER BY created_at DESC`, so the driver returns newest first.
        // We model two rows; resolver picks `results.first()` — the newest.
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(listOf("item_newer", "item_older"))

        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == "item_newer") {
            "Expected 'item_newer' (tiebreaker: most recently created) but got $result"
        }
        // Verify the SQL orders by created_at DESC (this is the tiebreaker contract)
        verify(entityManager).createNativeQuery(argThat<String> {
            contains("ORDER BY created_at DESC")
        })
    }

    @Test
    fun `resolveInventoryItemId T-23 returns null for random UUID with no matching row (no crash)`() {
        val skuId = UUID.randomUUID()
        val query = mock<Query>()
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.setParameter(eq("platform"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        // Must not throw — the method handles missing rows gracefully.
        val result = resolver.resolveInventoryItemId(skuId)

        assert(result == null) { "Expected null but got $result" }
    }
}
