package com.autoshipper.fulfillment.proxy.supplier

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.HttpClientErrorException
import java.nio.file.Files
import java.nio.file.Paths

class CjSupplierOrderAdapterWireMockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        private const val ORDER_ENDPOINT = "/api2.0/v1/shopping/order/createOrderV2"
    }

    private fun loadFixture(path: String): String =
        this::class.java.classLoader
            .getResource(path)
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: $path")

    private fun adapter(
        baseUrl: String = wireMock.baseUrl(),
        accessToken: String = "test-cj-access-token",
        logisticName: String = "",
        devStoreDryRun: Boolean = false
    ): CjSupplierOrderAdapter = CjSupplierOrderAdapter(
        baseUrl = baseUrl,
        accessToken = accessToken,
        logisticName = logisticName,
        objectMapper = jacksonObjectMapper(),
        devStoreDryRun = devStoreDryRun
    )

    private fun validRequest(
        orderNumber: String = "order-001",
        quantity: Int = 2,
        supplierVariantId: String = "vid-abc-123",
        supplierProductId: String = "pid-xyz-456",
        warehouseCountryCode: String? = null
    ): SupplierOrderRequest = SupplierOrderRequest(
        orderNumber = orderNumber,
        shippingAddress = ShippingAddress(
            customerName = "Jane Doe",
            addressLine1 = "456 Oak Ave",
            addressLine2 = "Suite 200",
            city = "Portland",
            province = "Oregon",
            country = "United States",
            countryCode = "US",
            zip = "97201",
            phone = "+1-503-555-0199"
        ),
        supplierProductId = supplierProductId,
        supplierVariantId = supplierVariantId,
        quantity = quantity,
        warehouseCountryCode = warehouseCountryCode
    )

    @Test
    fun `successful order placement returns Success with supplierOrderId`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)
        val success = result as SupplierOrderResult.Success
        assertThat(success.supplierOrderId).isEqualTo("2011152148163605")
    }

    @Test
    fun `out of stock error returns Failure with reason`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-out-of-stock.json"))
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Product stock insufficient")
    }

    @Test
    fun `invalid address error returns Failure with reason`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-invalid-address.json"))
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Param error")
    }

    @Test
    fun `auth failure 401 propagates as HttpClientErrorException`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Unauthorized"}""")
                )
        )

        assertThrows<HttpClientErrorException.Unauthorized> {
            adapter().placeOrder(validRequest())
        }
    }

    @Test
    fun `credential guard - blank credentials return Failure with no HTTP call`() {
        // Adapter with blank baseUrl and accessToken
        val blankAdapter = adapter(baseUrl = "", accessToken = "")

        val result = blankAdapter.placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).isEqualTo("CJ API credentials not configured")

        // Verify no HTTP requests were made to WireMock
        wireMock.verify(0, postRequestedFor(urlEqualTo(ORDER_ENDPOINT)))
    }

    @Test
    fun `request body verification - correct headers and body structure`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": {
                                    "orderId": "cj-verify-456"
                                }
                            }
                        """.trimIndent())
                )
        )

        val request = validRequest(
            orderNumber = "verify-order-789",
            quantity = 3,
            supplierVariantId = "vid-verify-001",
            supplierProductId = "pid-verify-002"
        )

        adapter().placeOrder(request)

        // Verify CJ-Access-Token header is present with correct value
        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withHeader("CJ-Access-Token", equalTo("test-cj-access-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("\"orderNumber\":\"verify-order-789\""))
                .withRequestBody(containing("\"shippingCustomerName\":\"Jane Doe\""))
                .withRequestBody(containing("\"shippingAddress\":\"456 Oak Ave\""))
                .withRequestBody(containing("\"shippingAddress2\":\"Suite 200\""))
                .withRequestBody(containing("\"shippingCity\":\"Portland\""))
                .withRequestBody(containing("\"shippingProvince\":\"Oregon\""))
                .withRequestBody(containing("\"shippingCountryCode\":\"US\""))
                .withRequestBody(containing("\"shippingZip\":\"97201\""))
                .withRequestBody(containing("\"shippingPhone\":\"+1-503-555-0199\""))
                .withRequestBody(containing("\"vid\":\"vid-verify-001\""))
                .withRequestBody(containing("\"quantity\":3"))
        )
    }

    @Test
    fun `null orderId in success response returns Failure via NullNode guard`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-null-fields.json"))
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Missing orderId")
    }

    @Test
    fun `null data node in response returns Failure`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": null
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Missing orderId")
    }

    @Test
    fun `HTTP 500 propagates as exception`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Internal Server Error"}""")
                )
        )

        assertThrows<HttpServerErrorException.InternalServerError> {
            adapter().placeOrder(validRequest())
        }
    }

    @Test
    fun `sends addressLine2 as separate shippingAddress2 field`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": { "orderId": "cj-addr-test" }
                            }
                        """.trimIndent())
                )
        )

        val request = SupplierOrderRequest(
            orderNumber = "addr-test-001",
            shippingAddress = ShippingAddress(
                customerName = "Jane Doe",
                addressLine1 = "123 Main St",
                addressLine2 = "Apt 4B",
                city = "Portland",
                province = "Oregon",
                country = "United States",
                countryCode = "US",
                zip = "97201",
                phone = "+1-503-555-0199"
            ),
            supplierProductId = "pid-001",
            supplierVariantId = "vid-001",
            quantity = 1
        )

        adapter().placeOrder(request)

        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withRequestBody(containing("\"shippingAddress\":\"123 Main St\""))
                .withRequestBody(containing("\"shippingAddress2\":\"Apt 4B\""))
        )
    }

    @Test
    fun `null address line2 omits shippingAddress2 field`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": { "orderId": "cj-nulladdr-test" }
                            }
                        """.trimIndent())
                )
        )

        val request = SupplierOrderRequest(
            orderNumber = "addr-test-002",
            shippingAddress = ShippingAddress(
                customerName = "Jane Doe",
                addressLine1 = "123 Main St",
                addressLine2 = null,
                city = "Portland",
                province = "Oregon",
                country = "United States",
                countryCode = "US",
                zip = "97201",
                phone = "+1-503-555-0199"
            ),
            supplierProductId = "pid-001",
            supplierVariantId = "vid-001",
            quantity = 1
        )

        adapter().placeOrder(request)

        val requests = wireMock.findAll(postRequestedFor(urlEqualTo(ORDER_ENDPOINT)))
        assertThat(requests).hasSize(1)
        assertThat(requests.first().bodyAsString).contains("\"shippingAddress\":\"123 Main St\"")
        assertThat(requests.first().bodyAsString).doesNotContain("shippingAddress2")
    }

    @Test
    fun `US warehouse mapping sends fromCountryCode US`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        adapter().placeOrder(validRequest(warehouseCountryCode = "US"))

        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withRequestBody(containing("\"fromCountryCode\":\"US\""))
        )
    }

    @Test
    fun `null warehouse mapping falls back to fromCountryCode CN`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        adapter().placeOrder(validRequest(warehouseCountryCode = null))

        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withRequestBody(containing("\"fromCountryCode\":\"CN\""))
        )
    }

    @Test
    fun `logisticName configured - included in request body`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        adapter(logisticName = "CJPacket").placeOrder(validRequest())

        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withRequestBody(containing("\"logisticName\":\"CJPacket\""))
        )
    }

    @Test
    fun `logisticName blank - omitted from request body`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        adapter(logisticName = "").placeOrder(validRequest())

        // Verify logisticName is NOT in the request body
        val requests = wireMock.findAll(postRequestedFor(urlEqualTo(ORDER_ENDPOINT)))
        assertThat(requests).hasSize(1)
        assertThat(requests.first().bodyAsString).doesNotContain("logisticName")
    }

    // =====================================================================
    // FR-030 / RAT-53 — CjSupplierOrderAdapter dry-run kill-switch (T-64..T-68)
    //
    // BR-6b / AD-3b: when `autoshipper.cj.dev-store-dry-run=true`, placeOrder()
    // short-circuits, logs a marker, and returns a stub id — making ZERO HTTP
    // calls to CJ. T-65 is the load-bearing safety assertion.
    // =====================================================================

    private var dryRunListAppender: ListAppender<ILoggingEvent>? = null

    private fun attachLogAppender(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(CjSupplierOrderAdapter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        dryRunListAppender = appender
        return appender
    }

    @AfterEach
    fun detachLogAppender() {
        dryRunListAppender?.let { appender ->
            val logger = LoggerFactory.getLogger(CjSupplierOrderAdapter::class.java) as Logger
            logger.detachAppender(appender)
            appender.stop()
        }
        dryRunListAppender = null
    }

    @Test
    fun `T-64 shortCircuitsWhenDryRunEnabled - returns Success with dry-run id and logs marker`() {
        val appender = attachLogAppender()

        val request = validRequest(
            orderNumber = "dryrun-order-001",
            quantity = 7,
            supplierVariantId = "dryrun-vid-xyz"
        )

        val result = adapter(devStoreDryRun = true).placeOrder(request)

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)
        val success = result as SupplierOrderResult.Success
        // ^dry-run-[0-9a-f-]{36}$ — UUID form
        assertThat(success.supplierOrderId).matches("^dry-run-[0-9a-f-]{36}$")

        // Assert INFO log contains the exact marker AND request identifiers
        val infoLogs = appender.list.filter { it.level == Level.INFO }
        assertThat(infoLogs)
            .`as`("At least one INFO log entry expected during dry-run")
            .isNotEmpty()
        val formatted = infoLogs.map { it.formattedMessage }
        assertThat(formatted)
            .`as`("INFO logs must contain exact [DEV-STORE DRY RUN] marker")
            .anyMatch { it.contains("[DEV-STORE DRY RUN]") }
        assertThat(formatted)
            .`as`("Log must include supplierVariantId (skuCode)")
            .anyMatch { it.contains("dryrun-vid-xyz") }
        assertThat(formatted)
            .`as`("Log must include quantity")
            .anyMatch { it.contains("qty=7") }
        assertThat(formatted)
            .`as`("Log must include orderNumber")
            .anyMatch { it.contains("dryrun-order-001") }
    }

    @Test
    fun `T-65 makesZeroHttpCallsWhenDryRunEnabled - CRITICAL safety assertion`() {
        // No stubs registered. If the adapter makes any HTTP call, WireMock returns
        // 404 and we'd still see it in the request journal. The verify line below
        // is the single load-bearing safety guarantee for the entire dry-run switch.
        val result = adapter(devStoreDryRun = true).placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)

        // THE line — if this fails, real CJ orders could get placed during dev-store runs.
        wireMock.verify(0, anyRequestedFor(anyUrl()))
    }

    @Test
    fun `T-66 makesHttpCallWhenDryRunDisabled - default path still posts to CJ`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/cj/create-order-success.json"))
                )
        )

        // devStoreDryRun defaults to false via the adapter() helper
        val result = adapter().placeOrder(
            validRequest(orderNumber = "normal-order-777", supplierVariantId = "vid-normal")
        )

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)

        // Exactly ONE POST to the CJ endpoint with expected body fields
        wireMock.verify(
            1,
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withRequestBody(containing("\"orderNumber\":\"normal-order-777\""))
                .withRequestBody(containing("\"vid\":\"vid-normal\""))
        )
    }

    @Test
    fun `T-67 dry-run result id has dry-run prefix and is consumable downstream`() {
        // Unit-level assertion on the returned SupplierOrderResult (the Spring-context
        // integration variant is deferred — see test-spec.md §5.3 deferred-or-note
        // clause; this unit assertion is the accepted lightweight form).
        val result = adapter(devStoreDryRun = true).placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)
        val success = result as SupplierOrderResult.Success
        assertThat(success.supplierOrderId)
            .`as`("Downstream consumers (SupplierOrderPlacementListener) rely on the dry-run- prefix to identify stub ids")
            .startsWith("dry-run-")
        // Confirm the UUID suffix is a valid UUID (36 chars including hyphens)
        val suffix = success.supplierOrderId.removePrefix("dry-run-")
        assertThat(suffix).hasSize(36)
        java.util.UUID.fromString(suffix) // throws if malformed — satisfies the format contract
    }

    @Test
    fun `T-68 application yml default for dev-store-dry-run is false`() {
        // Property-reading unit test (Spring context too heavy for this module —
        // the @SpringBootApplication lives in :app). We inspect the shipped
        // application.yml to assert the documented default per CLAUDE.md #13.
        val projectRoot = findProjectRoot()
        val applicationYml = projectRoot.resolve("modules/app/src/main/resources/application.yml")
        assertThat(Files.exists(applicationYml))
            .`as`("application.yml must exist at $applicationYml")
            .isTrue()

        val yamlText = Files.readString(applicationYml)

        // The key must be declared AND default to false. The env-var override form
        // `${AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN:false}` carries the `false` default.
        assertThat(yamlText)
            .`as`("application.yml must declare autoshipper.cj.dev-store-dry-run key")
            .contains("dev-store-dry-run:")
        assertThat(yamlText)
            .`as`("application.yml default for dev-store-dry-run MUST be false (CLAUDE.md #13)")
            .containsPattern("dev-store-dry-run:\\s*\\\$\\{AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN:false\\}")
    }

    private fun findProjectRoot(): java.nio.file.Path {
        // Walk upward from CWD until settings.gradle.kts is found.
        var current: java.nio.file.Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        throw IllegalStateException("Could not locate project root (settings.gradle.kts)")
    }
}
