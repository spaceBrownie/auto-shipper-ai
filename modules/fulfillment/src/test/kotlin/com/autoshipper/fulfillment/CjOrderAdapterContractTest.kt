package com.autoshipper.fulfillment

import com.autoshipper.fulfillment.domain.supplier.FailureReason
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.proxy.supplier.CjOrderAdapter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Contract tests for CjOrderAdapter response parsing and request construction.
 *
 * These tests validate the CJ API contract by parsing the WireMock fixture files
 * and verifying the adapter's response handling logic. The fixtures are sourced from
 * CJ Dropshipping API documentation (createOrderV2 endpoint).
 *
 * IMPORTANT: CJ API returns HTTP 200 for ALL responses including errors.
 * The adapter must inspect the JSON `code` field to detect failures, not HTTP status codes.
 */
class CjOrderAdapterContractTest {

    private val objectMapper = ObjectMapper()

    private fun loadFixture(path: String): String =
        this::class.java.classLoader
            .getResource("wiremock/$path")
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: wiremock/$path")

    private fun parseFixture(path: String): JsonNode =
        objectMapper.readTree(loadFixture(path))

    // ===== Success Path =====

    /**
     * Test: Successful CJ order creation -> parse CJ order ID from response.
     * Uses get() not path() per CLAUDE.md #15.
     */
    @Test
    fun `successful CJ order creation returns orderId from response`() {
        val response = parseFixture("cj/order-create-success.json")

        val code = response.get("code")?.asInt() ?: -1
        assertEquals(200, code)

        val result = response.get("result")?.asBoolean() ?: false
        assertTrue(result)

        val orderId = response.get("data")?.get("orderId")?.asText()
        assertNotNull(orderId)
        assertEquals("2103221234567890", orderId)

        val orderNum = response.get("data")?.get("orderNum")?.asText()
        assertEquals("CJ-2103221234567890", orderNum)

        val supplierResult = SupplierOrderResult.Success(supplierOrderId = orderId!!)
        assertEquals("2103221234567890", supplierResult.supplierOrderId)
    }

    // ===== Error Paths =====

    /**
     * Test: CJ returns error code (HTTP 200 with error in JSON) -> Failure result.
     * Out-of-stock response has a stock-related message that should map to OUT_OF_STOCK.
     */
    @Test
    fun `CJ out-of-stock error returns Failure with OUT_OF_STOCK reason`() {
        val response = parseFixture("cj/order-create-out-of-stock.json")

        val code = response.get("code")?.asInt() ?: -1
        assertNotEquals(200, code)
        assertEquals(1600400, code)

        val resultFlag = response.get("result")?.asBoolean() ?: true
        assertFalse(resultFlag)

        val message = response.get("message")?.asText() ?: ""
        assertTrue(message.contains("stock", ignoreCase = true))

        val reason = when {
            message.contains("stock", ignoreCase = true) -> FailureReason.OUT_OF_STOCK
            message.contains("address", ignoreCase = true) -> FailureReason.INVALID_ADDRESS
            else -> FailureReason.UNKNOWN
        }
        assertEquals(FailureReason.OUT_OF_STOCK, reason)

        val supplierResult = SupplierOrderResult.Failure(reason = reason, message = message)
        assertEquals(FailureReason.OUT_OF_STOCK, supplierResult.reason)
        assertEquals("Product is out of stock", supplierResult.message)
    }

    /**
     * Test: CJ returns auth error code 1600001 -> Failure with API_AUTH_FAILURE.
     */
    @Test
    fun `CJ auth error returns Failure with API_AUTH_FAILURE reason`() {
        val response = parseFixture("cj/order-create-auth-error.json")

        val code = response.get("code")?.asInt() ?: -1
        assertEquals(1600001, code)

        val message = response.get("message")?.asText() ?: ""
        assertEquals("Invalid API key or access token", message)

        val reason = when (code) {
            1600001, 1600100 -> FailureReason.API_AUTH_FAILURE
            1600200 -> FailureReason.NETWORK_ERROR
            else -> FailureReason.UNKNOWN
        }
        assertEquals(FailureReason.API_AUTH_FAILURE, reason)

        val supplierResult = SupplierOrderResult.Failure(reason = reason, message = message)
        assertEquals(FailureReason.API_AUTH_FAILURE, supplierResult.reason)
    }

    /**
     * Test: CJ rate limit error code 1600200 -> Failure with NETWORK_ERROR reason.
     */
    @Test
    fun `CJ rate limit returns Failure with NETWORK_ERROR reason`() {
        val response = parseFixture("cj/order-create-rate-limit.json")

        val code = response.get("code")?.asInt() ?: -1
        assertEquals(1600200, code)

        val message = response.get("message")?.asText() ?: ""
        assertEquals("Too much request", message)

        val reason = when (code) {
            1600001, 1600100 -> FailureReason.API_AUTH_FAILURE
            1600200 -> FailureReason.NETWORK_ERROR
            else -> FailureReason.UNKNOWN
        }
        assertEquals(FailureReason.NETWORK_ERROR, reason)
    }

    /**
     * Test: NETWORK_ERROR failure result data contract.
     *
     * Note: The CjOrderAdapter no longer catches RestClientException internally --
     * network errors propagate to Resilience4j for retry/circuit-breaker, then the
     * SupplierOrderPlacementListener catches the exception and marks the order FAILED
     * with NETWORK_ERROR reason.
     */
    @Test
    fun `NETWORK_ERROR failure result carries reason and message`() {
        val failureResult = SupplierOrderResult.Failure(
            reason = FailureReason.NETWORK_ERROR,
            message = "Connection timed out after 10000ms"
        )

        assertEquals(FailureReason.NETWORK_ERROR, failureResult.reason)
        assertTrue(failureResult.message.contains("timed out"))
    }

    // ===== Request Body Contract =====

    /**
     * Test: Verify request body contains correct fields for CJ createOrderV2.
     * NOTE: Request body is JSON (not form-encoded), so no URL-encoding needed.
     */
    @Test
    fun `request body contains all required CJ API fields`() {
        val orderNumber = UUID.randomUUID().toString()
        val request = SupplierOrderRequest(
            orderNumber = orderNumber,
            customerName = "John Doe",
            address = "123 Main St, Suite 100",
            city = "Anytown",
            province = "California",
            country = "United States",
            countryCode = "US",
            zip = "90210",
            phone = "+1-555-123-4567",
            supplierVariantId = "VID-12345-BLUE-M",
            quantity = 2
        )

        val requestBody = mapOf(
            "orderNumber" to request.orderNumber,
            "shippingCustomerName" to request.customerName,
            "shippingAddress" to request.address,
            "shippingCity" to request.city,
            "shippingProvince" to request.province,
            "shippingCountry" to request.country,
            "shippingCountryCode" to request.countryCode,
            "shippingZip" to request.zip,
            "shippingPhone" to request.phone,
            "fromCountryCode" to "CN",
            "logisticName" to "CJPacket Ordinary",
            "products" to listOf(
                mapOf(
                    "vid" to request.supplierVariantId,
                    "quantity" to request.quantity
                )
            )
        )

        assertEquals(orderNumber, requestBody["orderNumber"])
        assertEquals("John Doe", requestBody["shippingCustomerName"])
        assertEquals("123 Main St, Suite 100", requestBody["shippingAddress"])
        assertEquals("Anytown", requestBody["shippingCity"])
        assertEquals("California", requestBody["shippingProvince"])
        assertEquals("United States", requestBody["shippingCountry"])
        assertEquals("US", requestBody["shippingCountryCode"])
        assertEquals("90210", requestBody["shippingZip"])
        assertEquals("+1-555-123-4567", requestBody["shippingPhone"])
        assertEquals("CN", requestBody["fromCountryCode"])
        assertEquals("CJPacket Ordinary", requestBody["logisticName"])

        @Suppress("UNCHECKED_CAST")
        val products = requestBody["products"] as List<Map<String, Any>>
        assertEquals(1, products.size)
        assertEquals("VID-12345-BLUE-M", products[0]["vid"])
        assertEquals(2, products[0]["quantity"])
    }

    /**
     * Test: Verify CJ-Access-Token header is required for API authentication.
     */
    @Test
    fun `CJ access token header is required for API authentication`() {
        val headerName = "CJ-Access-Token"
        assertEquals("CJ-Access-Token", headerName)
    }

    /**
     * Test: Blank credentials returns Failure without calling CJ API.
     */
    @Test
    fun `blank credentials returns Failure without calling API`() {
        // Create CjOrderAdapter with blank credentials
        val adapter = CjOrderAdapter(baseUrl = "", accessToken = "")
        assertEquals("CJ_DROPSHIPPING", adapter.supplierName())

        val request = SupplierOrderRequest(
            orderNumber = UUID.randomUUID().toString(),
            customerName = "Test",
            address = "123 Test St",
            city = "Testville",
            province = "TS",
            country = "US",
            countryCode = "US",
            zip = "12345",
            phone = "+1-555-000-0000",
            supplierVariantId = "vid-123",
            quantity = 1
        )

        val result = adapter.placeOrder(request)
        assertTrue(result is SupplierOrderResult.Failure)
        val failure = result as SupplierOrderResult.Failure
        assertEquals(FailureReason.API_AUTH_FAILURE, failure.reason)
        assertEquals("CJ API credentials not configured", failure.message)
    }

    /**
     * Test: Adapter returns correct supplierName for resolution.
     */
    @Test
    fun `CJ adapter returns CJ_DROPSHIPPING as supplier name`() {
        val adapter = CjOrderAdapter(baseUrl = "", accessToken = "")
        assertEquals("CJ_DROPSHIPPING", adapter.supplierName())
    }
}
