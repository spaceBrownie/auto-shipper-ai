package com.autoshipper.portfolio.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedditDemandAdapterTest {

    @Mock
    private lateinit var authClient: RestClient

    @Mock
    private lateinit var apiClient: RestClient

    // Auth chain mocks
    @Mock
    private lateinit var authRequestBodyUriSpec: RestClient.RequestBodyUriSpec

    @Mock
    private lateinit var authRequestBodySpec: RestClient.RequestBodySpec

    @Mock
    private lateinit var authResponseSpec: RestClient.ResponseSpec

    // API chain mocks
    @Mock
    private lateinit var apiRequestHeadersUriSpec: RestClient.RequestHeadersUriSpec<*>

    @Mock
    private lateinit var apiRequestHeadersSpec: RestClient.RequestHeadersSpec<*>

    @Mock
    private lateinit var apiResponseSpec: RestClient.ResponseSpec

    private val objectMapper = ObjectMapper()

    private lateinit var adapter: RedditDemandAdapter

    private val clientId = "test-client-id"
    private val clientSecret = "test-client-secret"
    private val userAgent = "AutoShipperAI/1.0 test"
    private val subreddits = listOf("BuyItForLife", "shutupandtakemymoney")

    private fun tokenResponse(expiresIn: Long = 86400): JsonNode {
        return objectMapper.readTree(
            """
            {
                "access_token": "test-access-token-123",
                "token_type": "bearer",
                "expires_in": $expiresIn,
                "scope": "*"
            }
            """.trimIndent()
        )
    }

    private fun subredditResponse(subreddit: String): JsonNode {
        return objectMapper.readTree(
            """
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "1abc23",
                                "name": "t3_1abc23",
                                "title": "This cast iron skillet changed my cooking",
                                "subreddit": "$subreddit",
                                "ups": 4521,
                                "num_comments": 312,
                                "created_utc": 1710806400,
                                "subreddit_subscribers": 1250000
                            }
                        },
                        {
                            "data": {
                                "id": "2def45",
                                "name": "t3_2def45",
                                "title": "Best water bottle I have ever owned",
                                "subreddit": "$subreddit",
                                "ups": 1200,
                                "num_comments": 89,
                                "created_utc": 1710720000,
                                "subreddit_subscribers": 1250000
                            }
                        }
                    ]
                }
            }
            """.trimIndent()
        )
    }

    @BeforeEach
    fun setUp() {
        adapter = RedditDemandAdapter(
            baseUrl = "http://test",
            authUrl = "http://test/api/v1/access_token",
            clientId = clientId,
            clientSecret = clientSecret,
            userAgent = userAgent,
            subreddits = subreddits,
            sort = "hot",
            limitPerSubreddit = 25
        ).also {
            it.authClient = authClient
            it.apiClient = apiClient
        }

        stubAuthChain()
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubAuthChain() {
        whenever(authClient.post()).thenReturn(authRequestBodyUriSpec)
        whenever(authRequestBodyUriSpec.uri(any<String>())).thenReturn(authRequestBodySpec)
        whenever(authRequestBodySpec.header(any(), any())).thenReturn(authRequestBodySpec)
        whenever(authRequestBodySpec.contentType(any())).thenReturn(authRequestBodySpec)
        whenever(authRequestBodySpec.body(any<String>())).thenReturn(authRequestBodySpec)
        whenever(authRequestBodySpec.retrieve()).thenReturn(authResponseSpec)
        whenever(authResponseSpec.body(JsonNode::class.java)).thenReturn(tokenResponse())
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubApiChain(response: JsonNode) {
        whenever(apiClient.get()).thenReturn(apiRequestHeadersUriSpec as RestClient.RequestHeadersUriSpec<*>)
        whenever(apiRequestHeadersUriSpec.uri(any<String>(), any(), any(), any()))
            .thenReturn(apiRequestHeadersSpec as RestClient.RequestHeadersSpec<*>)
        whenever(apiRequestHeadersSpec.header(any(), any()))
            .thenReturn(apiRequestHeadersSpec as RestClient.RequestHeadersSpec<*>)
        whenever(apiRequestHeadersSpec.retrieve()).thenReturn(apiResponseSpec)
        whenever(apiResponseSpec.body(JsonNode::class.java)).thenReturn(response)
    }

    @Test
    fun `sourceType returns REDDIT`() {
        assertEquals("REDDIT", adapter.sourceType())
    }

    @Test
    fun `OAuth token acquisition - auth endpoint called with correct Basic header and form body`() {
        stubApiChain(subredditResponse("BuyItForLife"))

        adapter.fetch()

        verify(authClient).post()

        val expectedCredentials = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
        verify(authRequestBodySpec).header("Authorization", "Basic $expectedCredentials")
        verify(authRequestBodySpec).contentType(MediaType.APPLICATION_FORM_URLENCODED)

        val bodyCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(authRequestBodySpec).body(bodyCaptor.capture())
        val capturedBody = bodyCaptor.value
        val encodedGrantType = URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
        assertEquals("grant_type=$encodedGrantType", capturedBody)
    }

    @Test
    fun `token caching - auth endpoint called only once for two consecutive fetches`() {
        stubApiChain(subredditResponse("BuyItForLife"))

        adapter.fetch()
        adapter.fetch()

        verify(authClient, times(1)).post()
    }

    @Test
    fun `token refresh after expiry - auth endpoint called twice`() {
        // First token with normal expiry
        whenever(authResponseSpec.body(JsonNode::class.java)).thenReturn(tokenResponse(86400))
        stubApiChain(subredditResponse("BuyItForLife"))

        adapter.fetch()

        // Reset the cached token to simulate expiry
        adapter.resetToken()

        adapter.fetch()

        verify(authClient, times(2)).post()
    }

    @Test
    fun `happy path - posts mapped to correct RawCandidate fields`() {
        val response = subredditResponse("BuyItForLife")
        stubApiChain(response)

        val candidates = adapter.fetch()

        assertTrue(candidates.isNotEmpty(), "Should return candidates")

        val first = candidates.first()
        assertEquals("This cast iron skillet changed my cooking", first.productName)
        assertEquals("BuyItForLife", first.category)
        assertEquals("REDDIT", first.sourceType)
        assertTrue(first.description.startsWith("Reddit r/BuyItForLife:"))
        assertEquals(null, first.supplierUnitCost)
        assertEquals(null, first.estimatedSellingPrice)

        // Verify all 6 demand signal keys
        val signals = first.demandSignals
        assertEquals(6, signals.size, "Should have exactly 6 demand signal keys")
        assertTrue(signals.containsKey("post_id"))
        assertTrue(signals.containsKey("subreddit"))
        assertTrue(signals.containsKey("upvote_count"))
        assertTrue(signals.containsKey("comment_count"))
        assertTrue(signals.containsKey("post_age_hours"))
        assertTrue(signals.containsKey("subreddit_subscribers"))

        // Verify specific values
        assertEquals("t3_1abc23", signals["post_id"])
        assertEquals("BuyItForLife", signals["subreddit"])
        assertEquals("4521", signals["upvote_count"])
        assertEquals("312", signals["comment_count"])
        assertEquals("1250000", signals["subreddit_subscribers"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `partial failure - first subreddit succeeds, second throws, results from first returned`() {
        val singleSubredditAdapter = RedditDemandAdapter(
            baseUrl = "http://test",
            authUrl = "http://test/api/v1/access_token",
            clientId = clientId,
            clientSecret = clientSecret,
            userAgent = userAgent,
            subreddits = listOf("BuyItForLife", "failing_subreddit"),
            sort = "hot",
            limitPerSubreddit = 25
        ).also {
            it.authClient = authClient
            it.apiClient = apiClient
        }

        // For the first subreddit call, return valid data; for the second, throw
        var callCount = 0
        whenever(apiClient.get()).thenReturn(apiRequestHeadersUriSpec as RestClient.RequestHeadersUriSpec<*>)
        whenever(apiRequestHeadersUriSpec.uri(any<String>(), any(), any(), any()))
            .thenReturn(apiRequestHeadersSpec as RestClient.RequestHeadersSpec<*>)
        whenever(apiRequestHeadersSpec.header(any(), any()))
            .thenReturn(apiRequestHeadersSpec as RestClient.RequestHeadersSpec<*>)
        whenever(apiRequestHeadersSpec.retrieve()).thenAnswer {
            callCount++
            if (callCount > 1) {
                throw RuntimeException("Subreddit API error")
            }
            apiResponseSpec
        }
        whenever(apiResponseSpec.body(JsonNode::class.java))
            .thenReturn(subredditResponse("BuyItForLife"))

        val candidates = singleSubredditAdapter.fetch()

        // Should have 2 candidates from the first subreddit, none from the failed second
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.demandSignals["subreddit"] == "BuyItForLife" })
    }

    @Test
    fun `blank credentials returns empty list immediately`() {
        val blankAdapter = RedditDemandAdapter(
            baseUrl = "http://test",
            authUrl = "http://test/api/v1/access_token",
            clientId = "",
            clientSecret = clientSecret,
            userAgent = userAgent,
            subreddits = subreddits,
            sort = "hot",
            limitPerSubreddit = 25
        ).also {
            it.authClient = authClient
            it.apiClient = apiClient
        }

        val candidates = blankAdapter.fetch()

        assertTrue(candidates.isEmpty(), "Should return empty list for blank client-id")
        // Auth endpoint should never be called
        verify(authClient, times(0)).post()
    }

    @Test
    fun `URL encoding compliance - form body uses URLEncoder for grant_type value`() {
        stubApiChain(subredditResponse("BuyItForLife"))

        adapter.fetch()

        val bodyCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(authRequestBodySpec).body(bodyCaptor.capture())
        val capturedBody = bodyCaptor.value

        // The body should contain URLEncoder-encoded value
        val encodedValue = URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
        assertTrue(
            capturedBody.contains(encodedValue),
            "Form body should contain URL-encoded grant_type value: $encodedValue, but was: $capturedBody"
        )
        assertEquals("grant_type=$encodedValue", capturedBody)
    }
}
