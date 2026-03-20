package com.autoshipper.portfolio.proxy

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.math.BigDecimal

class CjDropshippingAdapterWireMockTest : WireMockAdapterTestBase() {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun adapter(): CjDropshippingAdapter =
        CjDropshippingAdapter(
            baseUrl = wireMock.baseUrl(),
            accessToken = "test-token"
        )

    @Test
    fun `happy path - products mapped with correct fields and demand signals`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/product-list-success.json"))
                )
        )

        val candidates = adapter().fetch()

        // 4 categories x 3 products each = 12 total
        assertValidRawCandidates(candidates, "CJ_DROPSHIPPING", expectedMinCount = 3)

        val first = candidates.first()
        assertThat(first.productName).isEqualTo("Stainless Steel Kitchen Knife Set")
        assertThat(first.supplierUnitCost).isEqualTo(
            Money.of(BigDecimal("12.50"), Currency.USD)
        )
        assertThat(first.estimatedSellingPrice).isEqualTo(
            Money.of(BigDecimal("31.25"), Currency.USD)
        )
        assertThat(first.sourceType).isEqualTo("CJ_DROPSHIPPING")

        assertSignalPresent(first, "cj_pid")
        assertSignalPresent(first, "cj_category_id")
        assertSignalPresent(first, "cj_product_image")
        assertThat(first.demandSignals["cj_pid"]).isEqualTo("04A22450-67F0-4617-A132-E7AE7F8963B0")
    }

    @Test
    fun `empty response - returns empty candidate list`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/product-list-empty.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `auth error - CJ returns HTTP 200 with error code 1600001, adapter returns empty list`() {
        // CJ API returns HTTP 200 for ALL responses — errors are in the JSON code field
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/error-401.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `rate limited - CJ returns HTTP 200 with error code 1600200, adapter returns empty list`() {
        // CJ API returns HTTP 200 for ALL responses — rate limits are in the JSON code field
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/error-429.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `malformed JSON - returns empty list without throwing`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"broken\":")
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }
}
