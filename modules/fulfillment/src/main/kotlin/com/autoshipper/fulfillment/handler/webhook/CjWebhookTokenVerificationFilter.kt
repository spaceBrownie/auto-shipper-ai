package com.autoshipper.fulfillment.handler.webhook

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Servlet filter that verifies the Bearer token on incoming CJ Dropshipping webhooks.
 *
 * This filter is NOT a Spring component — it is instantiated and registered via
 * FilterRegistrationBean by CjWebhookFilterConfig.
 *
 * Security considerations:
 * - Uses MessageDigest.isEqual() for constant-time comparison to prevent timing attacks.
 * - Never logs the token value (NFR-7).
 */
class CjWebhookTokenVerificationFilter(
    private val expectedToken: String
) : Filter {

    private val logger = LoggerFactory.getLogger(CjWebhookTokenVerificationFilter::class.java)

    init {
        if (expectedToken.isBlank()) {
            logger.warn("No CJ webhook secret configured — all webhooks will be rejected. Set CJ_DROPSHIPPING_WEBHOOK_SECRET.")
        }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val CONTENT_TYPE_JSON = "application/json"
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        if (expectedToken.isBlank()) {
            writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "CJ webhook secret not configured")
            return
        }

        val authHeader = httpRequest.getHeader(AUTHORIZATION_HEADER)
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid webhook token")
            return
        }

        val token = authHeader.removePrefix(BEARER_PREFIX).trim()

        if (!MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), expectedToken.toByteArray(Charsets.UTF_8))) {
            logger.warn("CJ webhook token verification failed from remote address: {}", httpRequest.remoteAddr)
            writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid webhook token")
            return
        }

        // Wrap AFTER auth succeeds — avoid buffering body for unauthenticated requests
        val wrapper = CachingRequestWrapper(httpRequest)
        chain.doFilter(wrapper, httpResponse)
    }

    private fun writeErrorResponse(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = CONTENT_TYPE_JSON
        response.writer.write("""{"error": "$message"}""")
        response.writer.flush()
    }
}
