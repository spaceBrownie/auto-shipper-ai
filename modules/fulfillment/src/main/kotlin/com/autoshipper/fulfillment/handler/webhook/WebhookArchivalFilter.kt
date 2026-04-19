package com.autoshipper.fulfillment.handler.webhook

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Servlet filter that archives raw webhook request bodies to disk for
 * PM-013 fixture-drift prevention.
 *
 * This class is NOT a Spring component. Spring Boot auto-registers any
 * `Filter` bean it finds (at every URL pattern with default order) when
 * no explicit `FilterRegistrationBean` constrains it. Annotating this
 * class with `@Component` would therefore leak the filter onto every
 * HTTP request even when `autoshipper.webhook-archival.enabled=false`
 * (the production default), because the config's `@ConditionalOnProperty`
 * only skips the registration bean, not the filter bean itself.
 *
 * Instead, the filter is instantiated directly inside
 * {@link WebhookArchivalFilterConfig}, which:
 * - restricts this filter to webhook URL patterns
 * - sets the filter order to run BEFORE ShopifyHmacVerificationFilter,
 *   so we capture payloads even when HMAC verification fails
 * - conditionally instantiates the entire configuration via
 *   {@code autoshipper.webhook-archival.enabled}
 *
 * The filter wraps the request in a {@link CachingRequestWrapper} so the
 * body can be read here and then re-read downstream by the HMAC filter
 * and the controller. I/O errors during archival never break the
 * filter chain; they are logged at WARN and the request forwards normally.
 */
class WebhookArchivalFilter(
    private val outputDir: String,
) : OncePerRequestFilter() {

    private val archivalLogger = LoggerFactory.getLogger(WebhookArchivalFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrappedRequest = CachingRequestWrapper(request)
        val bodyBytes = wrappedRequest.getCachedBody()

        val targetPath = buildArchivePath(request)
        try {
            Files.createDirectories(targetPath.parent)
            Files.write(targetPath, bodyBytes)
        } catch (e: IOException) {
            archivalLogger.warn(
                "Failed to archive webhook payload to {}: {}",
                targetPath,
                e.message,
            )
        } catch (e: SecurityException) {
            archivalLogger.warn(
                "Failed to archive webhook payload to {} (security): {}",
                targetPath,
                e.message,
            )
        }

        filterChain.doFilter(wrappedRequest, response)
    }

    private fun buildArchivePath(request: HttpServletRequest): Path {
        val date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val rawPath = request.servletPath?.takeIf { it.isNotBlank() }
            ?: request.requestURI
            ?: ""
        val slug = rawPath
            .trimStart('/')
            .replace('/', '-')
            .lowercase()
            .let { if (it.length > SLUG_MAX_LENGTH) it.substring(0, SLUG_MAX_LENGTH) else it }
            .ifBlank { "webhook" }
        // Epoch-ms alone is not unique — two webhooks in the same millisecond
        // (e.g. Shopify fires `orders/create` + `orders/paid` near-simultaneously)
        // would collide on filename and `Files.write` would silently overwrite.
        // Append a short random suffix so same-ms deliveries land in distinct files.
        val randomSuffix = java.util.UUID.randomUUID().toString().take(8)
        val filename = "$slug-${System.currentTimeMillis()}-$randomSuffix.json"
        return Paths.get(outputDir, date, filename)
    }

    companion object {
        private const val SLUG_MAX_LENGTH = 80
    }
}
