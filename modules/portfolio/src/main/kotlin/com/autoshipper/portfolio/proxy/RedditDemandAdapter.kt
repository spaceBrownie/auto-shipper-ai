package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
@Profile("!local")
class RedditDemandAdapter(
    @Value("\${reddit.api.base-url:https://oauth.reddit.com}") private val baseUrl: String,
    @Value("\${reddit.api.auth-url:https://www.reddit.com/api/v1/access_token}") private val authUrl: String,
    @Value("\${reddit.api.client-id}") private val clientId: String,
    @Value("\${reddit.api.client-secret}") private val clientSecret: String,
    @Value("\${reddit.api.user-agent:AutoShipperAI/1.0}") private val userAgent: String,
    @Value("\${reddit.api.subreddits:BuyItForLife,shutupandtakemymoney}") private val subreddits: List<String>,
    @Value("\${reddit.api.sort:hot}") private val sort: String,
    @Value("\${reddit.api.limit-per-subreddit:25}") private val limitPerSubreddit: Int
) : DemandSignalProvider {

    private val logger = LoggerFactory.getLogger(RedditDemandAdapter::class.java)

    private var authClient: RestClient = RestClient.builder()
        .baseUrl(authUrl.substringBefore("/api/"))
        .build()

    private var apiClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .build()

    private val tokenLock = ReentrantLock()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt: Instant = Instant.MIN

    /** Internal constructor for testing — accepts pre-built RestClients. */
    internal constructor(
        clientId: String,
        clientSecret: String,
        userAgent: String,
        subreddits: List<String>,
        sort: String,
        limitPerSubreddit: Int,
        authClient: RestClient,
        apiClient: RestClient
    ) : this(
        baseUrl = "http://test",
        authUrl = "http://test/api/v1/access_token",
        clientId = clientId,
        clientSecret = clientSecret,
        userAgent = userAgent,
        subreddits = subreddits,
        sort = sort,
        limitPerSubreddit = limitPerSubreddit
    ) {
        this.authClient = authClient
        this.apiClient = apiClient
    }

    override fun sourceType(): String = "REDDIT"

    override fun fetch(): List<RawCandidate> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            logger.warn("Reddit API credentials are blank — skipping fetch")
            return emptyList()
        }

        logger.info("Fetching demand signals from Reddit ({} subreddits)", subreddits.size)

        val token = getAccessToken()
        val candidates = mutableListOf<RawCandidate>()

        for (subreddit in subreddits) {
            try {
                val response = apiClient.get()
                    .uri("/r/{subreddit}/{sort}?limit={limit}&raw_json=1", subreddit, sort, limitPerSubreddit)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(JsonNode::class.java)

                val children = response?.path("data")?.path("children")
                if (children != null && children.isArray) {
                    for (child in children) {
                        val post = child.path("data")
                        candidates.add(mapPost(post, subreddit))
                    }
                }
            } catch (e: Exception) {
                logger.warn("Reddit fetch for r/{} failed: {}", subreddit, e.message)
            }
        }

        logger.info("Reddit returned {} candidates", candidates.size)
        return candidates
    }

    internal fun getAccessToken(): String {
        val now = Instant.now()
        if (cachedToken != null && now.isBefore(tokenExpiresAt)) {
            return cachedToken!!
        }

        tokenLock.withLock {
            // Double-check after acquiring lock
            val nowInLock = Instant.now()
            if (cachedToken != null && nowInLock.isBefore(tokenExpiresAt)) {
                return cachedToken!!
            }

            val credentials = Base64.getEncoder()
                .encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))

            val encodedGrantType = URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
            val body = "grant_type=$encodedGrantType"

            val response = authClient.post()
                .uri("/api/v1/access_token")
                .header("Authorization", "Basic $credentials")
                .header("User-Agent", userAgent)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)

            cachedToken = response?.path("access_token")?.asText()
                ?: throw IllegalStateException("Failed to obtain Reddit access token")
            val expiresIn = response.path("expires_in").asLong(86400)
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 60)

            logger.info("Obtained Reddit access token (expires in {}s)", expiresIn)
            return cachedToken!!
        }
    }

    /** Resets the cached token — exposed for testing only. */
    internal fun resetToken() {
        tokenLock.withLock {
            cachedToken = null
            tokenExpiresAt = Instant.MIN
        }
    }

    private fun mapPost(post: JsonNode, subreddit: String): RawCandidate {
        val title = post.path("title").asText("Untitled")
        val truncatedTitle = if (title.length > 100) title.substring(0, 100) else title

        return RawCandidate(
            productName = title,
            category = subreddit,
            description = "Reddit r/$subreddit: $truncatedTitle",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "post_id" to post.path("name").asText(post.path("id").asText("")),
                "subreddit" to subreddit,
                "upvote_count" to post.path("ups").asInt(0).toString(),
                "comment_count" to post.path("num_comments").asInt(0).toString(),
                "post_age_hours" to calculateAgeInHours(post.path("created_utc").asDouble(0.0)),
                "subreddit_subscribers" to post.path("subreddit_subscribers").asLong(0).toString()
            )
        )
    }

    private fun calculateAgeInHours(createdUtc: Double): String {
        if (createdUtc <= 0) return "0"
        val createdInstant = Instant.ofEpochSecond(createdUtc.toLong())
        val ageSeconds = Instant.now().epochSecond - createdInstant.epochSecond
        return (ageSeconds / 3600).coerceAtLeast(0).toString()
    }
}
