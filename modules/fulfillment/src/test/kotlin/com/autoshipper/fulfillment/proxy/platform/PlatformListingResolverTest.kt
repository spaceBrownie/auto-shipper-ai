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
}
