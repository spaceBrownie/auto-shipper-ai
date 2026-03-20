package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@Profile("!local")
class YouTubeDataAdapter(
    @Value("\${youtube.api.base-url}") private val baseUrl: String,
    @Value("\${youtube.api.key}") private val apiKey: String,
    @Value("\${youtube.api.search-terms}") private val searchTerms: List<String>,
    @Value("\${youtube.api.max-results-per-search:10}") private val maxResultsPerSearch: Int
) : DemandSignalProvider {

    private val logger = LoggerFactory.getLogger(YouTubeDataAdapter::class.java)

    private var restClient: RestClient = RestClient.builder().baseUrl(baseUrl).build()

    /** Internal constructor for testing — accepts pre-built RestClient. */
    internal constructor(
        baseUrl: String, apiKey: String, searchTerms: List<String>,
        maxResultsPerSearch: Int, restClient: RestClient
    ) : this(baseUrl, apiKey, searchTerms, maxResultsPerSearch) {
        this.restClient = restClient
    }

    override fun sourceType(): String = "YOUTUBE_DATA"

    override fun fetch(): List<RawCandidate> {
        if (apiKey.isBlank()) {
            logger.warn("YouTube API key is blank — skipping fetch")
            return emptyList()
        }

        logger.info("Fetching product review videos from YouTube Data API v3")
        val candidates = mutableListOf<RawCandidate>()

        for (searchTerm in searchTerms) {
            try {
                candidates.addAll(fetchForSearchTerm(searchTerm))
            } catch (e: Exception) {
                logger.warn("YouTube search for '{}' failed: {}", searchTerm, e.message)
            }
        }

        logger.info("YouTube Data API returned {} candidates", candidates.size)
        return candidates
    }

    private fun fetchForSearchTerm(searchTerm: String): List<RawCandidate> {
        val searchResponse = restClient.get()
            .uri { uri ->
                uri.path("/search")
                    .queryParam("part", "snippet")
                    .queryParam("q", searchTerm)
                    .queryParam("type", "video")
                    .queryParam("maxResults", maxResultsPerSearch)
                    .queryParam("key", apiKey)
                    .build()
            }
            .retrieve()
            .body(JsonNode::class.java)

        val items = searchResponse?.path("items")
        if (items == null || !items.isArray || items.isEmpty) {
            return emptyList()
        }

        val videoIds = mutableListOf<String>()
        val channelIds = mutableSetOf<String>()
        val snippetMap = mutableMapOf<String, JsonNode>()

        for (item in items) {
            val videoId = item.path("id").path("videoId").asText("")
            if (videoId.isBlank()) continue
            videoIds.add(videoId)
            val channelId = item.path("snippet").path("channelId").asText("")
            if (channelId.isNotBlank()) channelIds.add(channelId)
            snippetMap[videoId] = item.path("snippet")
        }

        if (videoIds.isEmpty()) return emptyList()

        val videoStats = fetchVideoStatistics(videoIds)
        val channelStats = fetchChannelStatistics(channelIds.toList())

        return videoIds.mapNotNull { videoId ->
            val snippet = snippetMap[videoId] ?: return@mapNotNull null
            val stats = videoStats[videoId]
            val channelId = snippet.path("channelId").asText("")
            val chStats = channelStats[channelId]

            RawCandidate(
                productName = snippet.path("title").asText("Unknown"),
                category = "Product Review",
                description = "YouTube: $searchTerm",
                sourceType = sourceType(),
                supplierUnitCost = null,
                estimatedSellingPrice = null,
                demandSignals = mapOf(
                    "video_id" to videoId,
                    "view_count" to (stats?.path("viewCount")?.asText("0") ?: "0"),
                    "like_count" to (stats?.path("likeCount")?.asText("0") ?: "0"),
                    "comment_count" to (stats?.path("commentCount")?.asText("0") ?: "0"),
                    "channel_subscriber_count" to (chStats?.path("subscriberCount")?.asText("0") ?: "0"),
                    "publish_date" to snippet.path("publishedAt").asText(""),
                    "search_term" to searchTerm
                )
            )
        }
    }

    private fun fetchVideoStatistics(videoIds: List<String>): Map<String, JsonNode> {
        val response = restClient.get()
            .uri { uri ->
                uri.path("/videos")
                    .queryParam("part", "statistics")
                    .queryParam("id", videoIds.joinToString(","))
                    .queryParam("key", apiKey)
                    .build()
            }
            .retrieve()
            .body(JsonNode::class.java)

        val result = mutableMapOf<String, JsonNode>()
        val items = response?.path("items")
        if (items != null && items.isArray) {
            for (item in items) {
                val id = item.path("id").asText("")
                if (id.isNotBlank()) {
                    result[id] = item.path("statistics")
                }
            }
        }
        return result
    }

    private fun fetchChannelStatistics(channelIds: List<String>): Map<String, JsonNode> {
        if (channelIds.isEmpty()) return emptyMap()

        val response = restClient.get()
            .uri { uri ->
                uri.path("/channels")
                    .queryParam("part", "statistics")
                    .queryParam("id", channelIds.joinToString(","))
                    .queryParam("key", apiKey)
                    .build()
            }
            .retrieve()
            .body(JsonNode::class.java)

        val result = mutableMapOf<String, JsonNode>()
        val items = response?.path("items")
        if (items != null && items.isArray) {
            for (item in items) {
                val id = item.path("id").asText("")
                if (id.isNotBlank()) {
                    result[id] = item.path("statistics")
                }
            }
        }
        return result
    }
}
