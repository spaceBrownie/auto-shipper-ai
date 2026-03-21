package com.autoshipper.pricing.proxy

import com.autoshipper.catalog.persistence.PlatformListingEntity
import com.autoshipper.catalog.persistence.PlatformListingRepository
import com.autoshipper.catalog.proxy.platform.PlatformAdapter
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient

class ShopifyPriceSyncAdapterWireMockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun loadFixture(path: String): String {
        return this::class.java.classLoader
            .getResource("wiremock/$path")
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: wiremock/$path")
    }

    private val platformAdapter: PlatformAdapter = mock()
    private val platformListingRepository: PlatformListingRepository = mock()
    private val skuId = SkuId.new()
    private val newPrice = Money.of(BigDecimal("29.99"), Currency.USD)

    private fun adapter(): ShopifyPriceSyncAdapter {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        val restClient = RestClient.builder()
            .baseUrl(wireMock.baseUrl())
            .requestFactory(JdkClientHttpRequestFactory(httpClient))
            .build()
        return ShopifyPriceSyncAdapter(
            restClient,
            platformAdapter,
            platformListingRepository,
            "test-access-token"
        )
    }

    @Test
    fun `delegationPath - delegates to PlatformAdapter when listing exists with variantId`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = "var-456",
            currentPriceAmount = BigDecimal("19.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        adapter().syncPrice(skuId, newPrice)

        verify(platformAdapter).updatePrice("var-456", newPrice)
        assertThat(wireMock.allServeEvents).isEmpty()
    }

    @Test
    fun `delegationPath - updates listing entity price and timestamp`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = "var-456",
            currentPriceAmount = BigDecimal("19.99"),
            currency = "USD",
            status = "ACTIVE"
        )
        val originalUpdatedAt = entity.updatedAt

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        adapter().syncPrice(skuId, newPrice)

        assertThat(entity.currentPriceAmount).isEqualTo(BigDecimal("29.9900"))
        assertThat(entity.updatedAt).isAfterOrEqualTo(originalUpdatedAt)
        verify(platformListingRepository).save(entity)
    }

    @Test
    fun `fallbackPath - sends auth header and correct body`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/variants/${skuId.value}.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/variant-update-200.json"))
                )
        )

        adapter().syncPrice(skuId, newPrice)

        wireMock.verify(
            putRequestedFor(urlEqualTo("/admin/api/2024-01/variants/${skuId.value}.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
                .withRequestBody(matchingJsonPath("$.variant.price", equalTo("29.9900")))
        )
    }

    @Test
    fun `fallbackPath - 401 throws exception`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/variants/${skuId.value}.json"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/error-401.json"))
                )
        )

        assertThrows<HttpClientErrorException.Unauthorized> {
            adapter().syncPrice(skuId, newPrice)
        }
    }

    @Test
    fun `fallbackPath - 404 throws exception`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/variants/${skuId.value}.json"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/error-404.json"))
                )
        )

        assertThrows<HttpClientErrorException.NotFound> {
            adapter().syncPrice(skuId, newPrice)
        }
    }
}
