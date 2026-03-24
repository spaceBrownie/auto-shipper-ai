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
class VendorSkuResolverTest {

    @Mock
    lateinit var entityManager: EntityManager

    @InjectMocks
    lateinit var resolver: VendorSkuResolver

    @Test
    fun `resolveVendorId returns vendor UUID when active assignment exists`() {
        val skuId = UUID.randomUUID()
        val expectedVendorId = UUID.randomUUID()
        val query = mock<Query>()

        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(listOf(expectedVendorId))

        val result = resolver.resolveVendorId(skuId)

        assert(result == expectedVendorId) {
            "Expected vendor ID $expectedVendorId but got $result"
        }

        verify(entityManager).createNativeQuery(argThat<String> {
            contains("vendor_sku_assignments") && contains("sku_id") && contains("active = true")
        })
        verify(query).setParameter("skuId", skuId)
    }

    @Test
    fun `resolveVendorId returns null when no active assignment exists`() {
        val skuId = UUID.randomUUID()
        val query = mock<Query>()

        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(query)
        whenever(query.setParameter(eq("skuId"), any())).thenReturn(query)
        whenever(query.resultList).thenReturn(emptyList<Any>())

        val result = resolver.resolveVendorId(skuId)

        assert(result == null) {
            "Expected null but got $result"
        }
    }
}
