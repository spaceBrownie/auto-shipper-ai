package com.autoshipper.portfolio.proxy

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient

class RedditDemandAdapterWireMockTest : WireMockAdapterTestBase() {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun adapter(): RedditDemandAdapter =
        RedditDemandAdapter(
            baseUrl = "http://localhost",
            authUrl = "http://localhost/api/v1/access_token",
            clientId = "test-id",
            clientSecret = "test-secret",
            userAgent = "TestAgent/1.0",
            subreddits = listOf("BuyItForLife"),
            sort = "hot",
            limitPerSubreddit = 25
        ).also {
            it.authClient = RestClient.builder().baseUrl(wireMock.baseUrl()).build()
            it.apiClient = RestClient.builder().baseUrl(wireMock.baseUrl()).build()
        }

    private fun stubTokenSuccess() {
        wireMock.stubFor(
            post(urlEqualTo("/api/v1/access_token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("reddit/token-success.json"))
                )
        )
    }

    private fun stubSubredditSuccess() {
        wireMock.stubFor(
            get(urlPathEqualTo("/r/BuyItForLife/hot"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("reddit/subreddit-success.json"))
                )
        )
    }

    @Test
    fun `happy path - auth then subreddit fetch produces 3 candidates with correct signals`() {
        stubTokenSuccess()
        stubSubredditSuccess()

        val candidates = adapter().fetch()

        assertValidRawCandidates(candidates, "REDDIT", expectedMinCount = 3)
        assertThat(candidates).hasSize(3)

        val first = candidates.first()
        assertThat(first.productName).contains("Ninja Blast")
        assertThat(first.sourceType).isEqualTo("REDDIT")

        assertSignalPresent(first, "post_id")
        assertSignalPresent(first, "subreddit")
        assertSignalPresent(first, "upvote_count")
        assertSignalPresent(first, "comment_count")
        assertSignalPresent(first, "subreddit_subscribers")

        assertThat(first.demandSignals["post_id"]).isEqualTo("t3_post001")
        assertThat(first.demandSignals["subreddit"]).isEqualTo("BuyItForLife")
        assertThat(first.demandSignals["upvote_count"]).isEqualTo("2400")
        assertThat(first.demandSignals["comment_count"]).isEqualTo("185")
        assertThat(first.demandSignals["subreddit_subscribers"]).isEqualTo("1850000")

        // Verify auth request had correct headers
        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/v1/access_token"))
                .withHeader("Authorization", containing("Basic "))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
        )
    }

    @Test
    fun `token caching - second fetch reuses cached token, only 1 auth request`() {
        stubTokenSuccess()
        stubSubredditSuccess()

        val adapterInstance = adapter()
        adapterInstance.fetch()
        adapterInstance.fetch()

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/v1/access_token")))
    }

    @Test
    fun `OAuth auth failure 401 - returns empty list without throwing`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/v1/access_token"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("reddit/token-error-401.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `rate limited 429 - returns empty list without throwing`() {
        stubTokenSuccess()

        wireMock.stubFor(
            get(urlPathEqualTo("/r/BuyItForLife/hot"))
                .willReturn(
                    aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("reddit/error-429.json"))
                )
        )

        val candidates = adapter().fetch()

        assertThat(candidates).isEmpty()
    }
}
