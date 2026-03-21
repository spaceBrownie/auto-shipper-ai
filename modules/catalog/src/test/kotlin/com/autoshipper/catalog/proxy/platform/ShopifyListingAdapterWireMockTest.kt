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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Instant

class ShopifyListingAdapterWireMockTest {

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

    private fun adapter(accessToken: String = "test-access-token"): ShopifyListingAdapter {
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        val restClient = RestClient.builder()
            .baseUrl(wireMock.baseUrl())
            .requestFactory(requestFactory)
            .build()
        return ShopifyListingAdapter(restClient, accessToken, ObjectMapper())
    }

    private fun buildLaunchReadySku(): LaunchReadySku {
        val skuId = SkuId.new()
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
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
    fun `productCreation - extracts numeric productId and variantId from Shopify response`() {
        wireMock.stubFor(
            post(urlEqualTo("/admin/api/2024-01/products.json"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/product-create-201.json"))
                )
        )

        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("29.99"), Currency.USD)

        val result = adapter().listSku(launchReadySku, price)

        assertThat(result.externalListingId).isEqualTo("1072481062")
        assertThat(result.externalVariantId).isEqualTo("1070325053")

        wireMock.verify(
            postRequestedFor(urlEqualTo("/admin/api/2024-01/products.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
                .withRequestBody(matchingJsonPath("$.product.title"))
                .withRequestBody(matchingJsonPath("$.product.variants[0].price"))
                .withRequestBody(matchingJsonPath("$.product.variants[0].sku"))
                .withRequestBody(matchingJsonPath("$.product.variants[0].inventory_management"))
        )
    }

    @Test
    fun `pauseSku - sends PUT with draft status`() {
        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/products/1072481062.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/product-update-200.json"))
                )
        )

        adapter().pauseSku("1072481062")

        wireMock.verify(
            putRequestedFor(urlEqualTo("/admin/api/2024-01/products/1072481062.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
                .withRequestBody(matchingJsonPath("$.product.status", equalTo("draft")))
        )
    }

    @Test
    fun `archiveSku - sends PUT with archived status`() {
        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/products/1072481062.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/product-update-200.json"))
                )
        )

        adapter().archiveSku("1072481062")

        wireMock.verify(
            putRequestedFor(urlEqualTo("/admin/api/2024-01/products/1072481062.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
                .withRequestBody(matchingJsonPath("$.product.status", equalTo("archived")))
        )
    }

    @Test
    fun `updatePrice - sends string-formatted price in variant body`() {
        wireMock.stubFor(
            put(urlEqualTo("/admin/api/2024-01/variants/808950810.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/variant-update-200.json"))
                )
        )

        val newPrice = Money.of(BigDecimal("29.99"), Currency.USD)
        adapter().updatePrice("808950810", newPrice)

        wireMock.verify(
            putRequestedFor(urlEqualTo("/admin/api/2024-01/variants/808950810.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
                .withRequestBody(matchingJsonPath("$.variant.price", equalTo("29.9900")))
        )
    }

    @Test
    fun `error 401 - throws exception on unauthorized response`() {
        wireMock.stubFor(
            post(urlEqualTo("/admin/api/2024-01/products.json"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/error-401.json"))
                )
        )

        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("29.99"), Currency.USD)

        assertThatThrownBy { adapter().listSku(launchReadySku, price) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `error 422 - throws exception on validation error response`() {
        wireMock.stubFor(
            post(urlEqualTo("/admin/api/2024-01/products.json"))
                .willReturn(
                    aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/error-422.json"))
                )
        )

        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("29.99"), Currency.USD)

        assertThatThrownBy { adapter().listSku(launchReadySku, price) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `error 429 - throws exception on rate limited response`() {
        wireMock.stubFor(
            post(urlEqualTo("/admin/api/2024-01/products.json"))
                .willReturn(
                    aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "2.0")
                        .withBody(loadFixture("shopify/error-429.json"))
                )
        )

        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("29.99"), Currency.USD)

        assertThatThrownBy { adapter().listSku(launchReadySku, price) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `blankAccessToken - throws IllegalStateException without making HTTP call`() {
        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("29.99"), Currency.USD)

        assertThatThrownBy { adapter(accessToken = "").listSku(launchReadySku, price) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Shopify access token is not configured")

        assertThat(wireMock.allServeEvents).isEmpty()
    }
}
