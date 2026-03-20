package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.CostEnvelope
import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.StressTestedMargin
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
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
import java.time.Instant

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopifyListingAdapterTest {

    @Mock
    private lateinit var shopifyRestClient: RestClient

    @Mock
    private lateinit var requestBodyUriSpec: RestClient.RequestBodyUriSpec

    @Mock
    private lateinit var requestBodySpec: RestClient.RequestBodySpec

    @Mock
    private lateinit var responseSpec: RestClient.ResponseSpec

    @Mock
    private lateinit var requestHeadersUriSpec: RestClient.RequestHeadersUriSpec<*>

    private val objectMapper = ObjectMapper()
    private lateinit var adapter: ShopifyListingAdapter

    private val skuId = SkuId.new()
    private val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
    private val price = Money.of(BigDecimal("49.99"), Currency.USD)

    @BeforeEach
    fun setUp() {
        adapter = ShopifyListingAdapter(shopifyRestClient, "test-access-token", objectMapper)
    }

    private fun buildLaunchReadySku(): LaunchReadySku {
        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = Money.of(BigDecimal("5.00"), Currency.USD),
            inboundShipping = Money.of(BigDecimal("1.00"), Currency.USD),
            outboundShipping = Money.of(BigDecimal("3.00"), Currency.USD),
            platformFee = Money.of(BigDecimal("1.00"), Currency.USD),
            processingFee = Money.of(BigDecimal("0.50"), Currency.USD),
            packagingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            returnHandlingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerAcquisitionCost = Money.of(BigDecimal("2.00"), Currency.USD),
            warehousingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerServiceCost = Money.of(BigDecimal("0.50"), Currency.USD),
            refundAllowance = Money.of(BigDecimal("0.50"), Currency.USD),
            chargebackAllowance = Money.of(BigDecimal("0.25"), Currency.USD),
            taxesAndDuties = Money.of(BigDecimal("0.25"), Currency.USD),
            verifiedAt = Instant.now()
        )
        val margin = StressTestedMargin(Percentage.of(BigDecimal("35.00")))
        return LaunchReadySku(sku, envelope, margin)
    }

    @Test
    fun `listSku creates Shopify product and returns external IDs`() {
        val launchReadySku = buildLaunchReadySku()
        val shopifyResponse = """
            {
              "product": {
                "id": "7890123456",
                "title": "Test Product",
                "status": "active",
                "variants": [
                  {
                    "id": "4567890123",
                    "price": "49.9900"
                  }
                ]
              }
            }
        """.trimIndent()

        whenever(shopifyRestClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.body(String::class.java)).thenReturn(shopifyResponse)

        val result = adapter.listSku(launchReadySku, price)

        assertEquals("7890123456", result.externalListingId)
        assertEquals("4567890123", result.externalVariantId)
        verify(shopifyRestClient).post()
    }

    @Test
    fun `listSku throws when access token is blank`() {
        val blankTokenAdapter = ShopifyListingAdapter(shopifyRestClient, "", objectMapper)
        val launchReadySku = buildLaunchReadySku()

        assertThrows(IllegalStateException::class.java) {
            blankTokenAdapter.listSku(launchReadySku, price)
        }

        verify(shopifyRestClient, never()).post()
    }

    @Test
    fun `listSku throws when Shopify response is missing product`() {
        val launchReadySku = buildLaunchReadySku()
        val badResponse = """{"error": "something went wrong"}"""

        whenever(shopifyRestClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.body(String::class.java)).thenReturn(badResponse)

        assertThrows(RuntimeException::class.java) {
            adapter.listSku(launchReadySku, price)
        }
    }

    @Test
    fun `pauseSku sends PUT with draft status`() {
        whenever(shopifyRestClient.put()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>(), any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build())

        adapter.pauseSku("7890123456")

        val bodyCaptor = argumentCaptor<Any>()
        verify(requestBodySpec).body(bodyCaptor.capture())

        @Suppress("UNCHECKED_CAST")
        val body = bodyCaptor.firstValue as Map<String, Map<String, String>>
        assertEquals("draft", body["product"]?.get("status"))
    }

    @Test
    fun `archiveSku sends PUT with archived status`() {
        whenever(shopifyRestClient.put()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>(), any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build())

        adapter.archiveSku("7890123456")

        val bodyCaptor = argumentCaptor<Any>()
        verify(requestBodySpec).body(bodyCaptor.capture())

        @Suppress("UNCHECKED_CAST")
        val body = bodyCaptor.firstValue as Map<String, Map<String, String>>
        assertEquals("archived", body["product"]?.get("status"))
    }

    @Test
    fun `updatePrice sends PUT with correct price`() {
        whenever(shopifyRestClient.put()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>(), any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.body(any<Any>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build())

        val newPrice = Money.of(BigDecimal("59.99"), Currency.USD)
        adapter.updatePrice("4567890123", newPrice)

        val bodyCaptor = argumentCaptor<Any>()
        verify(requestBodySpec).body(bodyCaptor.capture())

        @Suppress("UNCHECKED_CAST")
        val body = bodyCaptor.firstValue as Map<String, Map<String, String>>
        assertEquals("59.9900", body["variant"]?.get("price"))
    }

    @Test
    fun `updatePrice does nothing when access token is blank`() {
        val blankTokenAdapter = ShopifyListingAdapter(shopifyRestClient, "", objectMapper)

        blankTokenAdapter.updatePrice("4567890123", price)

        verify(shopifyRestClient, never()).put()
    }

    @Test
    fun `getFees returns 2 percent transaction fee for Basic plan`() {
        val fees = adapter.getFees("Electronics", Money.of(BigDecimal("100.00"), Currency.USD))

        assertEquals(Money.of(BigDecimal("2.0000"), Currency.USD), fees.transactionFee)
        assertEquals(Money.of(BigDecimal.ZERO, Currency.USD), fees.listingFee)
    }
}
