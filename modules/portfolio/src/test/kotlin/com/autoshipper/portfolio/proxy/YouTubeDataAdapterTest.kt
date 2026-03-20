package com.autoshipper.portfolio.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.function.Function

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YouTubeDataAdapterTest {

    private val objectMapper = ObjectMapper()

    private fun buildSearchResponse(vararg videos: Triple<String, String, String>): JsonNode {
        val items = videos.map { (videoId, title, channelId) ->
            mapOf(
                "id" to mapOf("videoId" to videoId),
                "snippet" to mapOf(
                    "title" to title,
                    "channelId" to channelId,
                    "publishedAt" to "2026-03-01T12:00:00Z",
                    "channelTitle" to "TestChannel"
                )
            )
        }
        return objectMapper.valueToTree(mapOf("items" to items))
    }

    private fun buildVideosResponse(vararg stats: Triple<String, String, String>): JsonNode {
        val items = stats.map { (videoId, viewCount, likeCount) ->
            mapOf(
                "id" to videoId,
                "statistics" to mapOf(
                    "viewCount" to viewCount,
                    "likeCount" to likeCount,
                    "commentCount" to "320"
                )
            )
        }
        return objectMapper.valueToTree(mapOf("items" to items))
    }

    private fun buildChannelsResponse(vararg channels: Pair<String, String>): JsonNode {
        val items = channels.map { (channelId, subscriberCount) ->
            mapOf(
                "id" to channelId,
                "statistics" to mapOf(
                    "subscriberCount" to subscriberCount
                )
            )
        }
        return objectMapper.valueToTree(mapOf("items" to items))
    }

    private fun buildEmptyResponse(): JsonNode = objectMapper.valueToTree(mapOf("items" to emptyList<Any>()))

    private fun buildVideosResponseWithMissingStats(videoId: String): JsonNode {
        val items = listOf(
            mapOf(
                "id" to videoId,
                "statistics" to mapOf(
                    "viewCount" to "100"
                    // likeCount and commentCount intentionally omitted
                )
            )
        )
        return objectMapper.valueToTree(mapOf("items" to items))
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockRestClient(responses: Map<String, JsonNode>): RestClient {
        val restClient = mock<RestClient>()
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()
        val requestHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
        val responseSpec = mock<RestClient.ResponseSpec>()

        whenever(restClient.get()).thenReturn(requestHeadersUriSpec as RestClient.RequestHeadersUriSpec<Nothing>)
        whenever((requestHeadersUriSpec as RestClient.RequestHeadersUriSpec<Nothing>).uri(any<Function<org.springframework.web.util.UriBuilder, URI>>()))
            .thenAnswer { invocation ->
                val uriFunction = invocation.arguments[0] as Function<org.springframework.web.util.UriBuilder, URI>
                val uriBuilder = org.springframework.web.util.DefaultUriBuilderFactory("https://test.youtube.com")
                    .builder()
                val uri = uriFunction.apply(uriBuilder)
                val path = uri.path

                // Store the path for later use in body() call
                whenever(requestHeadersSpec.retrieve()).thenReturn(responseSpec)

                val responseNode = when {
                    path.contains("/search") -> responses["search"] ?: buildEmptyResponse()
                    path.contains("/videos") -> responses["videos"] ?: buildEmptyResponse()
                    path.contains("/channels") -> responses["channels"] ?: buildEmptyResponse()
                    else -> buildEmptyResponse()
                }
                whenever(responseSpec.body(JsonNode::class.java)).thenReturn(responseNode)

                requestHeadersSpec
            }

        return restClient
    }

    @Test
    fun `happy path - valid search, video, and channel responses produce correct RawCandidates`() {
        val responses = mapOf(
            "search" to buildSearchResponse(
                Triple("vid1", "Best Kitchen Gadgets 2026", "UCxxx")
            ),
            "videos" to buildVideosResponse(
                Triple("vid1", "150000", "5000")
            ),
            "channels" to buildChannelsResponse(
                "UCxxx" to "250000"
            )
        )

        val restClient = mockRestClient(responses)
        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "test-api-key",
            searchTerms = listOf("kitchen gadgets"),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        val candidates = adapter.fetch()

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("Best Kitchen Gadgets 2026", candidate.productName)
        assertEquals("Product Review", candidate.category)
        assertEquals("YouTube: kitchen gadgets", candidate.description)
        assertEquals("YOUTUBE_DATA", candidate.sourceType)
        assertEquals(null, candidate.supplierUnitCost)
        assertEquals(null, candidate.estimatedSellingPrice)

        // Verify all 7 demand signal keys
        assertEquals(7, candidate.demandSignals.size)
        assertEquals("vid1", candidate.demandSignals["video_id"])
        assertEquals("150000", candidate.demandSignals["view_count"])
        assertEquals("5000", candidate.demandSignals["like_count"])
        assertEquals("320", candidate.demandSignals["comment_count"])
        assertEquals("250000", candidate.demandSignals["channel_subscriber_count"])
        assertEquals("2026-03-01T12:00:00Z", candidate.demandSignals["publish_date"])
        assertEquals("kitchen gadgets", candidate.demandSignals["search_term"])
    }

    @Test
    fun `sourceType returns YOUTUBE_DATA`() {
        val restClient = mockRestClient(emptyMap())
        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "test-api-key",
            searchTerms = emptyList(),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        assertEquals("YOUTUBE_DATA", adapter.sourceType())
    }

    @Test
    fun `partial failure - first search term succeeds, second throws exception`() {
        // We need a RestClient that succeeds on first call and fails on second.
        // Since both use /search, we use a counter-based approach.
        val searchResponse = buildSearchResponse(
            Triple("vid1", "Good Product", "UCaaa")
        )
        val videosResponse = buildVideosResponse(
            Triple("vid1", "1000", "100")
        )
        val channelsResponse = buildChannelsResponse(
            "UCaaa" to "50000"
        )

        val restClient = mock<RestClient>()
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()

        @Suppress("UNCHECKED_CAST")
        whenever(restClient.get()).thenReturn(requestHeadersUriSpec as RestClient.RequestHeadersUriSpec<Nothing>)

        var searchCallCount = 0

        @Suppress("UNCHECKED_CAST")
        whenever((requestHeadersUriSpec as RestClient.RequestHeadersUriSpec<Nothing>).uri(any<Function<org.springframework.web.util.UriBuilder, URI>>()))
            .thenAnswer { invocation ->
                val uriFunction = invocation.arguments[0] as Function<org.springframework.web.util.UriBuilder, URI>
                val uriBuilder = org.springframework.web.util.DefaultUriBuilderFactory("https://test.youtube.com")
                    .builder()
                val uri = uriFunction.apply(uriBuilder)
                val path = uri.path

                val requestHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
                val responseSpec = mock<RestClient.ResponseSpec>()
                whenever(requestHeadersSpec.retrieve()).thenReturn(responseSpec)

                if (path.contains("/search")) {
                    searchCallCount++
                    if (searchCallCount > 1) {
                        throw RuntimeException("API rate limit exceeded")
                    }
                    whenever(responseSpec.body(JsonNode::class.java)).thenReturn(searchResponse)
                } else if (path.contains("/videos")) {
                    whenever(responseSpec.body(JsonNode::class.java)).thenReturn(videosResponse)
                } else if (path.contains("/channels")) {
                    whenever(responseSpec.body(JsonNode::class.java)).thenReturn(channelsResponse)
                }

                requestHeadersSpec
            }

        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "test-api-key",
            searchTerms = listOf("good term", "bad term"),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        val candidates = adapter.fetch()

        assertEquals(1, candidates.size)
        assertEquals("Good Product", candidates[0].productName)
    }

    @Test
    fun `empty response - API returns empty items array`() {
        val responses = mapOf(
            "search" to buildEmptyResponse()
        )

        val restClient = mockRestClient(responses)
        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "test-api-key",
            searchTerms = listOf("empty search"),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        val candidates = adapter.fetch()

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `blank API key returns empty list immediately`() {
        val restClient = mockRestClient(emptyMap())
        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "   ",
            searchTerms = listOf("should not execute"),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        val candidates = adapter.fetch()

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `null statistics fields handled gracefully with defaults`() {
        val responses = mapOf(
            "search" to buildSearchResponse(
                Triple("vid1", "Product With Missing Stats", "UCbbb")
            ),
            "videos" to buildVideosResponseWithMissingStats("vid1"),
            "channels" to buildChannelsResponse(
                "UCbbb" to "1000"
            )
        )

        val restClient = mockRestClient(responses)
        val adapter = YouTubeDataAdapter(
            baseUrl = "https://test.youtube.com",
            apiKey = "test-api-key",
            searchTerms = listOf("partial stats"),
            maxResultsPerSearch = 10
        ).also { it.restClient = restClient }

        val candidates = adapter.fetch()

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("100", candidate.demandSignals["view_count"])
        // Missing likeCount and commentCount should default to "0"
        assertEquals("0", candidate.demandSignals["like_count"])
        assertEquals("0", candidate.demandSignals["comment_count"])
    }
}
