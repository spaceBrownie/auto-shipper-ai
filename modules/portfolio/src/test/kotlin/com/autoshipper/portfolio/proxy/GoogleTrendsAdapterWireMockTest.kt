package com.autoshipper.portfolio.proxy

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class GoogleTrendsAdapterWireMockTest : WireMockAdapterTestBase() {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun adapter(): GoogleTrendsAdapter =
        GoogleTrendsAdapter(geo = "US").also {
            it.feedUrl = wireMock.baseUrl() + "/trending/rss"
        }

    @Test
    fun `happy path - RSS items mapped with correct fields and demand signals`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/trending/rss"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(loadFixture("trends/rss-feed-success.xml"))
                )
        )

        val candidates = adapter().fetch()

        assertValidRawCandidates(candidates, "GOOGLE_TRENDS", expectedMinCount = 3)
        assertThat(candidates).hasSize(3)

        val first = candidates[0]
        assertThat(first.productName).isEqualTo("Portable Blender")
        assertThat(first.category).isEqualTo("Trending")
        assertThat(first.sourceType).isEqualTo("GOOGLE_TRENDS")
        assertThat(first.supplierUnitCost).isNull()
        assertThat(first.estimatedSellingPrice).isNull()

        assertSignalPresent(first, "approx_traffic")
        assertSignalPresent(first, "trend_date")
        assertSignalPresent(first, "geo")
        assertThat(first.demandSignals["approx_traffic"]).isEqualTo("5,000+")
        assertThat(first.demandSignals["geo"]).isEqualTo("US")
    }

    @Test
    fun `empty feed - returns empty candidate list`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/trending/rss"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(loadFixture("trends/rss-feed-empty.xml"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `malformed XML - returns empty list without throwing`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/trending/rss"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(loadFixture("trends/rss-feed-malformed.xml"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }
}
