package com.autoshipper.fulfillment.handler.webhook

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@ExtendWith(MockitoExtension::class)
class CjWebhookTokenVerificationFilterTest {

    private val validSecret = "cj-test-secret-token-12345"

    private lateinit var filter: CjWebhookTokenVerificationFilter

    @BeforeEach
    fun setUp() {
        filter = CjWebhookTokenVerificationFilter(validSecret)
    }

    /**
     * Simple FilterChain that captures the request passed to it.
     */
    private class CapturingFilterChain : FilterChain {
        var capturedRequest: ServletRequest? = null
        var capturedResponse: ServletResponse? = null

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            capturedRequest = request
            capturedResponse = response
        }
    }

    private fun createRequest(body: String, authHeader: String? = null): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            setContent(body.toByteArray(Charsets.UTF_8))
            method = "POST"
            requestURI = "/webhooks/cj/tracking"
            if (authHeader != null) {
                addHeader("Authorization", authHeader)
            }
        }
    }

    @Test
    fun `valid token passes through and chained request is CachingRequestWrapper`() {
        val body = """{"type":"LOGISTIC","params":{"orderId":"abc"}}"""
        val request = createRequest(body, "Bearer $validSecret")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest != null) { "Filter should chain the request through" }
        assert(response.status != 401) { "Response should not be 401" }
        assert(chain.capturedRequest is CachingRequestWrapper) {
            "Chained request should be a CachingRequestWrapper"
        }

        val wrapper = chain.capturedRequest as CachingRequestWrapper
        val readBody = String(wrapper.inputStream.readAllBytes(), Charsets.UTF_8)
        assert(readBody == body) { "Body should be readable from the chained wrapper" }
    }

    @Test
    fun `invalid token returns 401`() {
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, "Bearer wrong-secret")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain the request through" }
        assert(response.status == 401) { "Response should be 401" }
        assert(response.contentType == "application/json") { "Content type should be application/json" }
        assert(response.contentAsString.contains("Invalid webhook token")) {
            "Response body should contain error message"
        }
    }

    @Test
    fun `missing Authorization header returns 401`() {
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, authHeader = null)
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain the request through" }
        assert(response.status == 401) { "Response should be 401" }
        assert(response.contentType == "application/json") { "Content type should be application/json" }
        assert(response.contentAsString.contains("Invalid webhook token")) {
            "Response body should contain error message"
        }
    }

    @Test
    fun `blank configured secret rejects all requests`() {
        val blankFilter = CjWebhookTokenVerificationFilter("")
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, "Bearer some-token")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        blankFilter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain when secret is blank" }
        assert(response.status == 401) { "Response should be 401 when secret is blank" }
        assert(response.contentAsString.contains("CJ webhook secret not configured")) {
            "Response body should indicate secret not configured"
        }
    }

    @Test
    fun `whitespace-only configured secret rejects all requests`() {
        val blankFilter = CjWebhookTokenVerificationFilter("   ")
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, "Bearer some-token")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        blankFilter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain when secret is whitespace" }
        assert(response.status == 401) { "Response should be 401 when secret is whitespace" }
        assert(response.contentAsString.contains("CJ webhook secret not configured")) {
            "Response body should indicate secret not configured"
        }
    }

    @Test
    fun `token without Bearer prefix fails`() {
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, validSecret)
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain without Bearer prefix" }
        assert(response.status == 401) { "Response should be 401 without Bearer prefix" }
        assert(response.contentAsString.contains("Invalid webhook token")) {
            "Response body should contain error message"
        }
    }

    @Test
    fun `Bearer with extra whitespace around token is trimmed and accepted`() {
        val body = """{"type":"LOGISTIC","params":{"orderId":"abc"}}"""
        val request = createRequest(body, "Bearer   $validSecret   ")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest != null) { "Filter should chain the request through with trimmed token" }
        assert(response.status != 401) { "Response should not be 401 for trimmed token" }
    }

    @Test
    fun `constant-time comparison prevents timing attacks - different length tokens rejected`() {
        val body = """{"type":"LOGISTIC"}"""
        val request = createRequest(body, "Bearer x")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.capturedRequest == null) { "Filter should NOT chain with short token" }
        assert(response.status == 401) { "Response should be 401 for short token" }
    }
}
