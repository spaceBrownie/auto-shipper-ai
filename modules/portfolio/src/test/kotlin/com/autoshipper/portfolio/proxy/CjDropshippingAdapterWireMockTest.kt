package com.autoshipper.portfolio.proxy

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
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
        assertSignalPresent(first, "cj_warehouse_inventory_num")
        assertThat(first.demandSignals["cj_pid"]).isEqualTo("04A22450-67F0-4617-A132-E7AE7F8963B0")
        assertThat(first.demandSignals["cj_warehouse_inventory_num"]).isEqualTo("500")

        // Verify verifiedWarehouse=1 and countryCode=US query params are sent
        wireMock.verify(
            getRequestedFor(urlPathEqualTo("/product/listV2"))
                .withQueryParam("verifiedWarehouse", equalTo("1"))
                .withQueryParam("countryCode", equalTo("US"))
        )
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

    @Test
    fun `zero inventory products excluded - returns empty list`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/product-list-zero-inventory.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `null and absent inventory products excluded - fail closed`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/product-list-null-inventory.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `mixed inventory - only positive inventory products returned`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/product/listV2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("cj/product-list-mixed-inventory.json"))
                )
        )

        val candidates = adapter().fetch()

        // 7 products in fixture, only 2 have positive warehouseInventoryNum (GOOD-INV-001, GOOD-INV-002)
        // The stub returns the same fixture for all 4 category requests: 2 passing × 4 categories = 8
        assertThat(candidates).hasSize(8)
        assertThat(candidates.map { it.demandSignals["cj_pid"] }).contains("GOOD-INV-001", "GOOD-INV-002")
        assertThat(candidates.map { it.demandSignals["cj_pid"] }).doesNotContain(
            "ZERO-INV-MIX", "NULL-INV-MIX", "ABSENT-INV-MIX", "NEG-INV-MIX", "STR-INV-MIX"
        )
    }
}
