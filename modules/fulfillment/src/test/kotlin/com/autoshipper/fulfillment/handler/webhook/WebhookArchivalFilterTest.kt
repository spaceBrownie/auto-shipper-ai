package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookFilterConfig
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tests for WebhookArchivalFilter (FR-030 / RAT-53).
 *
 * Covers:
 *  - T-42..T-52: filter behavior and configuration wiring.
 *
 * Uses direct filter instantiation for T-42, T-44, T-46..T-51 so we can inject
 * a @TempDir output directory. Uses @SpringBootTest only for T-43 (bean absence),
 * T-45 (ordering), T-49 (url pattern registration), T-52 (explicit order value).
 */
class WebhookArchivalFilterTest {

    /**
     * Minimal test boot app. Excludes JPA/DataSource autoconfig — the archival/HMAC
     * components are pure servlet filters and don't need a datasource.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class,
            DataSourceTransactionManagerAutoConfiguration::class,
            HibernateJpaAutoConfiguration::class,
            JpaRepositoriesAutoConfiguration::class,
        ],
    )
    @ComponentScan(
        basePackageClasses = [WebhookArchivalFilter::class, ShopifyWebhookFilterConfig::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [
                    WebhookArchivalFilter::class,
                    WebhookArchivalFilterConfig::class,
                    ShopifyWebhookFilterConfig::class,
                ],
            ),
        ],
    )
    class TestApp

    private val today: String = LocalDate.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun findArchived(root: Path, slugPrefix: String): Path? {
        val dir = root.resolve(today)
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(slugPrefix) }
                .findFirst()
                .orElse(null)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-42 — writes a file when enabled and body is non-empty
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-42 writes archive file for non-empty shopify webhook body`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        val body = """{"order_id": 12345, "total_price": "49.99"}"""
        val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
            servletPath = "/webhooks/shopify/orders-create"
            setContent(body.toByteArray(Charsets.UTF_8))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val archived = findArchived(tmp, "webhooks-shopify-orders-create-")
            ?: error("Expected archived file under $tmp/$today/")
        val archivedBytes = Files.readAllBytes(archived)
        assert(archivedBytes.contentEquals(body.toByteArray(Charsets.UTF_8))) {
            "Archived bytes must equal request body exactly"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-43 — disabled by default: WebhookArchivalFilterConfig bean not registered
    // ─────────────────────────────────────────────────────────────────────────
    @SpringBootTest(classes = [TestApp::class])
    @TestPropertySource(
        properties = [
            // Satisfy ShopifyWebhookProperties so the context loads.
            "shopify.webhook.secrets=test-secret",
            // Do NOT set autoshipper.webhook-archival.enabled — verify default=disabled.
        ],
    )
    class DefaultArchivalDisabledTest {
        @Autowired private lateinit var context: ApplicationContext

        @Test
        fun `T-43 archival config bean is absent when property unset`() {
            val beans = context.getBeanNamesForType(WebhookArchivalFilterConfig::class.java)
            assert(beans.isEmpty()) {
                "WebhookArchivalFilterConfig must NOT be registered by default (found: ${beans.toList()})"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-44 — directory-write failure is logged but filter chain proceeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-44 unwritable output-dir logs WARN and forwards chain unchanged`() {
        // Point at a path whose parent is a regular FILE, so createDirectories fails.
        val blockedParent = Files.createTempFile("archival-blocked-", ".dat")
        try {
            val filter = WebhookArchivalFilter(blockedParent.toString())
            val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
                servletPath = "/webhooks/shopify/orders-create"
                setContent("""{"x":1}""".toByteArray(Charsets.UTF_8))
            }
            val response = MockHttpServletResponse()

            var chainCalled = false
            val chain = FilterChain { req, _ ->
                chainCalled = true
                // Downstream must still receive the body.
                val downstreamBody = String(req.inputStream.readAllBytes(), Charsets.UTF_8)
                assert(downstreamBody == """{"x":1}""") {
                    "Downstream must receive original body, got: $downstreamBody"
                }
            }

            filter.doFilter(request, response, chain)
            assert(chainCalled) { "Filter chain must be invoked even when archival write fails" }
            assert(response.status == HttpServletResponse.SC_OK) {
                "Response status must not be changed by a failed archival write"
            }
        } finally {
            Files.deleteIfExists(blockedParent)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-45 — archival order < HMAC order
    // T-52 — archival order equals -9 exactly
    // T-49 — url patterns restricted to /webhooks/{shopify,cj}/*
    // ─────────────────────────────────────────────────────────────────────────
    @SpringBootTest(classes = [TestApp::class])
    @TestPropertySource(
        properties = [
            "autoshipper.webhook-archival.enabled=true",
            "shopify.webhook.secrets=test-secret",
        ],
    )
    class ArchivalRegistrationTest {
        @Autowired private lateinit var context: ApplicationContext

        private fun archivalRegistration(): FilterRegistrationBean<*> {
            @Suppress("UNCHECKED_CAST")
            val all = context.getBeansOfType(FilterRegistrationBean::class.java)
                as Map<String, FilterRegistrationBean<*>>
            return all.values.first { reg ->
                reg.filter?.let { it::class == WebhookArchivalFilter::class } == true ||
                    reg.filter is WebhookArchivalFilter
            }
        }

        private fun hmacRegistration(): FilterRegistrationBean<*> {
            @Suppress("UNCHECKED_CAST")
            val all = context.getBeansOfType(FilterRegistrationBean::class.java)
                as Map<String, FilterRegistrationBean<*>>
            return all.values.first { reg -> reg.filter is ShopifyHmacVerificationFilter }
        }

        @Test
        fun `T-45 archival order is numerically less than HMAC order`() {
            val archival = archivalRegistration()
            val hmac = hmacRegistration()
            assert(archival.order < hmac.order) {
                "Archival order (${archival.order}) must be < HMAC order (${hmac.order}) " +
                    "so archival runs FIRST and captures bodies even when HMAC rejects them"
            }
        }

        @Test
        fun `T-52 archival registration order is exactly -9`() {
            val archival = archivalRegistration()
            assert(archival.order == -9) {
                "Archival registration order must be -9 (regression guard for negative-before-1 decision). " +
                    "Was: ${archival.order}"
            }
        }

        @Test
        fun `T-49 archival filter urlPatterns only cover webhook paths`() {
            val archival = archivalRegistration()
            val patterns = archival.urlPatterns.toSet()
            assert(patterns == setOf("/webhooks/shopify/*", "/webhooks/cj/*")) {
                "Url patterns must be restricted to shopify+cj webhook paths (was $patterns)"
            }
            assert(patterns.none { it.contains("/actuator") }) {
                "Must never register against /actuator/* paths"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-46 — downstream filter still reads the body
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-46 downstream filter receives identical body bytes`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        val body = """{"line_items": [{"product_id": 1, "quantity": 2}]}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
            servletPath = "/webhooks/shopify/orders-create"
            setContent(bodyBytes)
        }
        val response = MockHttpServletResponse()

        var downstreamBytes: ByteArray? = null
        val chain = FilterChain { req, _ ->
            downstreamBytes = req.inputStream.readAllBytes()
        }

        filter.doFilter(request, response, chain)

        assert(downstreamBytes != null) { "Chain must be invoked" }
        assert(downstreamBytes!!.contentEquals(bodyBytes)) {
            "Downstream must read identical bytes — wrapper must re-expose the cached body"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-47 — writes to YYYY-MM-DD UTC subdirectory
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-47 archive path uses UTC YYYY-MM-DD subdirectory`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
            servletPath = "/webhooks/shopify/orders-create"
            setContent("{}".toByteArray(Charsets.UTF_8))
        }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val dateDir = tmp.resolve(today)
        assert(Files.isDirectory(dateDir)) {
            "Date subdirectory $today must exist; actual tmp contents: " +
                "${Files.list(tmp).use { it.toList() }}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-48 — back-to-back deliveries get distinct filenames (epoch-ms suffix)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-48 back-to-back deliveries produce distinct filenames`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        repeat(2) { i ->
            val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
                servletPath = "/webhooks/shopify/orders-create"
                setContent("""{"i":$i}""".toByteArray(Charsets.UTF_8))
            }
            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            // Force a clock tick so System.currentTimeMillis() differs between iterations.
            Thread.sleep(5)
        }

        val files = Files.list(tmp.resolve(today)).use { it.toList() }
        assert(files.size == 2) {
            "Expected 2 distinct archived files, got ${files.size}: ${files.map { it.fileName }}"
        }
        val filenames = files.map { it.fileName.toString() }.toSet()
        assert(filenames.size == 2) {
            "Filenames must be unique across back-to-back deliveries: $filenames"
        }
        filenames.forEach { name ->
            assert(Regex(""".*-\d{10,}\.json""").matches(name)) {
                "Filename must contain an epoch-ms timestamp: $name"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-50 — CJ webhook path uses cj-* filename slug
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-50 CJ webhook path archives under cj-prefixed slug`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        val request = MockHttpServletRequest("POST", "/webhooks/cj/tracking").apply {
            servletPath = "/webhooks/cj/tracking"
            setContent("""{"tracking":"X123"}""".toByteArray(Charsets.UTF_8))
        }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val archived = findArchived(tmp, "webhooks-cj-tracking-")
            ?: error("Expected file under $tmp/$today/ with slug 'webhooks-cj-tracking-*'")
        assert(archived.fileName.toString().contains("cj")) {
            "CJ webhook filename must include 'cj' identifier: ${archived.fileName}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-51 — empty body writes a zero-byte file (evidence still captured)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `T-51 empty body writes zero-byte file`(@TempDir tmp: Path) {
        val filter = WebhookArchivalFilter(tmp.toString())
        val request = MockHttpServletRequest("POST", "/webhooks/shopify/orders-create").apply {
            servletPath = "/webhooks/shopify/orders-create"
            setContent(ByteArray(0))
        }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val archived = findArchived(tmp, "webhooks-shopify-orders-create-")
            ?: error("Expected zero-byte file under $tmp/$today/")
        assert(Files.size(archived) == 0L) {
            "Empty body must produce a zero-byte file, got ${Files.size(archived)} bytes"
        }
    }
}
