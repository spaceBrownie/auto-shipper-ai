package com.autoshipper.portfolio.proxy

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient

class YouTubeDataAdapterWireMockTest : WireMockAdapterTestBase() {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun adapter(): YouTubeDataAdapter =
        YouTubeDataAdapter(
            baseUrl = "http://localhost",
            apiKey = "test-key",
            searchTerms = listOf("test product"),
            maxResultsPerSearch = 10
        ).also {
            it.restClient = RestClient.builder().baseUrl(wireMock.baseUrl()).build()
        }

    @Test
    fun `happy path - all 3 endpoints return data, produces 3 candidates with correct signals`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/search"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("youtube/search-success.json"))
                )
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/videos"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("youtube/videos-success.json"))
                )
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/channels"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("youtube/channels-success.json"))
                )
        )

        val candidates = adapter().fetch()

        assertValidRawCandidates(candidates, "YOUTUBE_DATA", expectedMinCount = 3)
        assertThat(candidates).hasSize(3)

        val first = candidates.first()
        assertThat(first.productName).isEqualTo("Best Portable Blender 2026 Review")
        assertThat(first.sourceType).isEqualTo("YOUTUBE_DATA")

        assertSignalPresent(first, "video_id")
        assertSignalPresent(first, "view_count")
        assertSignalPresent(first, "like_count")
        assertSignalPresent(first, "comment_count")
        assertSignalPresent(first, "channel_subscriber_count")
        assertSignalPresent(first, "publish_date")
        assertSignalPresent(first, "search_term")

        assertThat(first.demandSignals["video_id"]).isEqualTo("VID-TEST-001")
        assertThat(first.demandSignals["view_count"]).isEqualTo("125000")
        assertThat(first.demandSignals["like_count"]).isEqualTo("4200")
        assertThat(first.demandSignals["comment_count"]).isEqualTo("380")
        assertThat(first.demandSignals["channel_subscriber_count"]).isEqualTo("450000")
        assertThat(first.demandSignals["publish_date"]).isEqualTo("2026-03-15T14:00:00Z")
        assertThat(first.demandSignals["search_term"]).isEqualTo("test product")
    }

    @Test
    fun `invalid API key 403 - returns empty list without throwing`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/search"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("youtube/error-403-invalid-key.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `quota exceeded 403 - returns empty list without throwing`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/search"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("youtube/error-403-quota.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `malformed JSON - returns empty list without throwing`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/search"))
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
