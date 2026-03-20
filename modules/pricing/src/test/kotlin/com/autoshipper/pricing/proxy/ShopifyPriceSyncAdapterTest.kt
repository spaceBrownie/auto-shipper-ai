package com.autoshipper.pricing.proxy

import com.autoshipper.catalog.persistence.PlatformListingEntity
import com.autoshipper.catalog.persistence.PlatformListingRepository
import com.autoshipper.catalog.proxy.platform.PlatformAdapter
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopifyPriceSyncAdapterTest {

    @Mock
    private lateinit var shopifyRestClient: RestClient

    @Mock
    private lateinit var platformAdapter: PlatformAdapter

    @Mock
    private lateinit var platformListingRepository: PlatformListingRepository

    @Mock
    private lateinit var requestBodyUriSpec: RestClient.RequestBodyUriSpec

    @Mock
    private lateinit var requestBodySpec: RestClient.RequestBodySpec

    @Mock
    private lateinit var responseSpec: RestClient.ResponseSpec

    private lateinit var adapter: ShopifyPriceSyncAdapter
    private val skuId = SkuId.new()
    private val newPrice = Money.of(BigDecimal("59.99"), Currency.USD)

    @BeforeEach
    fun setUp() {
        adapter = ShopifyPriceSyncAdapter(shopifyRestClient, platformAdapter, platformListingRepository)
    }

    @Test
    fun `delegates to PlatformAdapter when platform listing exists`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = "var-456",
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        adapter.syncPrice(skuId, newPrice)

        verify(platformAdapter).updatePrice("var-456", newPrice)
        assertEquals(BigDecimal("59.9900"), entity.currentPriceAmount)
        verify(shopifyRestClient, never()).put()
    }

    @Test
    fun `falls back to direct Shopify PUT when no platform listing exists`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        whenever(shopifyRestClient.put()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>(), any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build())

        adapter.syncPrice(skuId, newPrice)

        verify(shopifyRestClient).put()
        verify(platformAdapter, never()).updatePrice(any(), any())
    }

    @Test
    fun `falls back to direct Shopify PUT when variant ID is null`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = null,
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)

        whenever(shopifyRestClient.put()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>(), any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build())

        adapter.syncPrice(skuId, newPrice)

        verify(shopifyRestClient).put()
        verify(platformAdapter, never()).updatePrice(any(), any())
    }
}
