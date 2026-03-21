package com.autoshipper.fulfillment.handler.webhook

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Servlet filter that verifies the HMAC-SHA256 signature on incoming Shopify webhooks.
 *
 * This filter is NOT a Spring component — it is instantiated and registered via
 * FilterRegistrationBean by ShopifyWebhookFilterConfig.
 *
 * Security considerations:
 * - Uses MessageDigest.isEqual() for constant-time comparison to prevent timing attacks.
 * - Supports multiple secrets for seamless key rotation.
 * - Never logs request body or HMAC header values (NFR-7).
 */
class ShopifyHmacVerificationFilter(
    secrets: List<String>
) : Filter {

    private val logger = LoggerFactory.getLogger(ShopifyHmacVerificationFilter::class.java)
    private val effectiveSecrets = secrets.filter { it.isNotBlank() }

    init {
        if (effectiveSecrets.isEmpty()) {
            logger.warn("No Shopify webhook secrets configured — all webhooks will be rejected. Set SHOPIFY_WEBHOOK_SECRETS.")
        }
    }

    companion object {
        private const val HMAC_HEADER = "X-Shopify-Hmac-SHA256"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val CONTENT_TYPE_JSON = "application/json"
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        if (effectiveSecrets.isEmpty()) {
            writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Webhook secrets not configured")
            return
        }

        val wrapper = CachingRequestWrapper(httpRequest)

        val hmacHeader = httpRequest.getHeader(HMAC_HEADER)
        if (hmacHeader == null) {
            writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Missing HMAC signature")
            return
        }

        val bodyBytes = wrapper.getCachedBody()

        for (secret in effectiveSecrets) {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
            mac.init(secretKeySpec)
            val computedDigest = mac.doFinal(bodyBytes)
            val computedHmac = Base64.getEncoder().encodeToString(computedDigest)

            if (MessageDigest.isEqual(computedHmac.toByteArray(), hmacHeader.toByteArray())) {
                chain.doFilter(wrapper, httpResponse)
                return
            }
        }

        logger.warn("Shopify webhook HMAC verification failed from remote address: {}", httpRequest.remoteAddr)
        writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid HMAC signature")
    }

    private fun writeErrorResponse(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = CONTENT_TYPE_JSON
        response.writer.write("""{"error": "$message"}""")
        response.writer.flush()
    }
}
