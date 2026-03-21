package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
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

class ShopifyPlatformFeeProviderWireMockTest {

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

    private fun adapter(
        accessToken: String = "test-access-token",
        estimatedOrderValue: BigDecimal = BigDecimal("100.00")
    ): ShopifyPlatformFeeProvider {
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        val restClient = RestClient.builder()
            .baseUrl(wireMock.baseUrl())
            .requestFactory(requestFactory)
            .build()
        return ShopifyPlatformFeeProvider(restClient, accessToken, estimatedOrderValue)
    }

    private fun stubShopResponse(fixturePath: String) {
        wireMock.stubFor(
            get(urlEqualTo("/admin/api/2024-01/shop.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture(fixturePath))
                )
        )
    }

    @Test
    fun `basicPlan - returns 2 percent fee`() {
        stubShopResponse("shopify/shop-basic-200.json")

        val fee = adapter().getFee()

        assertThat(fee).isEqualTo(Money.of(BigDecimal("2.00"), Currency.USD))
    }

    @Test
    fun `professionalPlan - returns 1 percent fee`() {
        stubShopResponse("shopify/shop-professional-200.json")

        val fee = adapter().getFee()

        assertThat(fee).isEqualTo(Money.of(BigDecimal("1.00"), Currency.USD))
    }

    @Test
    fun `unlimitedPlan - returns half percent fee`() {
        stubShopResponse("shopify/shop-unlimited-200.json")

        val fee = adapter().getFee()

        assertThat(fee).isEqualTo(Money.of(BigDecimal("0.50"), Currency.USD))
    }

    @Test
    fun `shopifyPlusPlan - returns zero fee`() {
        stubShopResponse("shopify/shop-shopify-plus-200.json")

        val fee = adapter().getFee()

        assertThat(fee).isEqualTo(Money.of(BigDecimal("0.00"), Currency.USD))
    }

    @Test
    fun `unknownPlan - falls back to default 2 percent rate`() {
        wireMock.stubFor(
            get(urlEqualTo("/admin/api/2024-01/shop.json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "shop": {
                                "id": 548380009,
                                "name": "My Test Store",
                                "plan_name": "custom",
                                "plan_display_name": "Custom Plan",
                                "currency": "USD"
                              }
                            }
                        """.trimIndent())
                )
        )

        val fee = adapter().getFee()

        assertThat(fee).isEqualTo(Money.of(BigDecimal("2.00"), Currency.USD))
    }

    @Test
    fun `error 401 - throws ProviderUnavailableException`() {
        wireMock.stubFor(
            get(urlEqualTo("/admin/api/2024-01/shop.json"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("shopify/error-401.json"))
                )
        )

        assertThatThrownBy { adapter().getFee() }
            .isInstanceOf(ProviderUnavailableException::class.java)
    }

    @Test
    fun `blankAccessToken - throws IllegalStateException without making HTTP call`() {
        assertThatThrownBy { adapter(accessToken = "").getFee() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Shopify access token is not configured")

        assertThat(wireMock.allServeEvents).isEmpty()
    }

    @Test
    fun `request - sends X-Shopify-Access-Token header`() {
        stubShopResponse("shopify/shop-basic-200.json")

        adapter().getFee()

        wireMock.verify(
            getRequestedFor(urlEqualTo("/admin/api/2024-01/shop.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("test-access-token"))
        )
    }
}
