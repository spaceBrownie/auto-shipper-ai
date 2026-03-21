package com.autoshipper.fulfillment.handler.webhook

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ExtendWith(MockitoExtension::class)
class ShopifyHmacVerificationFilterTest {

    private val secret1 = "test-secret-1"
    private val secret2 = "test-secret-2"

    private lateinit var filter: ShopifyHmacVerificationFilter

    @BeforeEach
    fun setUp() {
        filter = ShopifyHmacVerificationFilter(listOf(secret1, secret2))
    }

    private fun computeHmac(body: ByteArray, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(body))
    }

    private fun createRequest(body: String, hmacHeader: String? = null): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            setContent(body.toByteArray(Charsets.UTF_8))
            method = "POST"
            requestURI = "/webhooks/shopify/orders"
            if (hmacHeader != null) {
                addHeader("X-Shopify-Hmac-SHA256", hmacHeader)
            }
        }
    }

    @Test
    fun `valid HMAC with first secret chains request through`() {
        val body = """{"order_id": 12345}"""
        val hmac = computeHmac(body.toByteArray(Charsets.UTF_8), secret1)
        val request = createRequest(body, hmac)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request != null) { "Filter should chain the request through" }
        assert(response.status != 401) { "Response should not be 401" }
    }

    @Test
    fun `valid HMAC with second secret chains request through for key rotation`() {
        val body = """{"order_id": 67890}"""
        val hmac = computeHmac(body.toByteArray(Charsets.UTF_8), secret2)
        val request = createRequest(body, hmac)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request != null) { "Filter should chain the request through with rotated secret" }
        assert(response.status != 401) { "Response should not be 401 with rotated secret" }
    }

    @Test
    fun `invalid HMAC returns 401 and does not chain`() {
        val body = """{"order_id": 12345}"""
        val request = createRequest(body, "invalid-hmac-value")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request == null) { "Filter should NOT chain the request through" }
        assert(response.status == 401) { "Response should be 401" }
        assert(response.contentType == "application/json") { "Content type should be application/json" }
        assert(response.contentAsString.contains("Invalid HMAC signature")) {
            "Response body should contain error message"
        }
    }

    @Test
    fun `missing X-Shopify-Hmac-SHA256 header returns 401`() {
        val body = """{"order_id": 12345}"""
        val request = createRequest(body, hmacHeader = null)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request == null) { "Filter should NOT chain the request through" }
        assert(response.status == 401) { "Response should be 401" }
        assert(response.contentType == "application/json") { "Content type should be application/json" }
        assert(response.contentAsString.contains("Missing HMAC signature")) {
            "Response body should contain missing header error message"
        }
    }

    @Test
    fun `empty body with valid HMAC chains through`() {
        val body = ""
        val hmac = computeHmac(body.toByteArray(Charsets.UTF_8), secret1)
        val request = createRequest(body, hmac)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request != null) { "Filter should chain the request through for empty body" }
        assert(response.status != 401) { "Response should not be 401 for empty body with valid HMAC" }
    }

    @Test
    fun `chained request is a CachingRequestWrapper allowing body re-read`() {
        val body = """{"order_id": 99999, "total_price": "149.99"}"""
        val hmac = computeHmac(body.toByteArray(Charsets.UTF_8), secret1)
        val request = createRequest(body, hmac)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val chainedRequest = chain.request
        assert(chainedRequest is CachingRequestWrapper) { "Chained request should be a CachingRequestWrapper" }

        val wrapper = chainedRequest as CachingRequestWrapper
        val readBody = String(wrapper.inputStream.readAllBytes(), Charsets.UTF_8)
        assert(readBody == body) { "Body should be readable from the chained wrapper" }
    }

    @Test
    fun `HMAC computed against wrong body does not match`() {
        val body = """{"order_id": 12345}"""
        val differentBody = """{"order_id": 99999}"""
        val hmac = computeHmac(differentBody.toByteArray(Charsets.UTF_8), secret1)
        val request = createRequest(body, hmac)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assert(chain.request == null) { "Filter should NOT chain when HMAC was computed for different body" }
        assert(response.status == 401) { "Response should be 401 for mismatched body" }
    }

    @Test
    fun `blank secrets are filtered out - all requests rejected with 401`() {
        val blankFilter = ShopifyHmacVerificationFilter(listOf("", "  ", ""))
        val body = """{"order_id": 12345}"""
        val request = createRequest(body, "any-hmac-value")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        blankFilter.doFilter(request, response, chain)

        assert(chain.request == null) { "Filter should NOT chain when no valid secrets configured" }
        assert(response.status == 401) { "Response should be 401 when no valid secrets configured" }
        assert(response.contentAsString.contains("Webhook secrets not configured"))
    }

    @Test
    fun `empty secrets list rejects all requests with 401`() {
        val emptyFilter = ShopifyHmacVerificationFilter(emptyList())
        val body = """{"order_id": 12345}"""
        val request = createRequest(body, "any-hmac-value")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        emptyFilter.doFilter(request, response, chain)

        assert(chain.request == null) { "Filter should NOT chain when secrets list is empty" }
        assert(response.status == 401) { "Response should be 401 when secrets list is empty" }
        assert(response.contentAsString.contains("Webhook secrets not configured"))
    }
}
