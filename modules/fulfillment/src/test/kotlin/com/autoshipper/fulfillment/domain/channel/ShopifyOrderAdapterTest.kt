package com.autoshipper.fulfillment.domain.channel

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class ShopifyOrderAdapterTest {

    private val objectMapper = ObjectMapper()
    private val adapter = ShopifyOrderAdapter(objectMapper)

    private fun loadPayload(): String =
        javaClass.classLoader.getResource("shopify/orders-create-webhook.json")!!.readText()

    @Test
    fun `parse extracts order ID, number, email, and currency`() {
        val order = adapter.parse(loadPayload())

        assert(order.channelOrderId == "820982911946154500") {
            "Expected order ID '820982911946154500' but got '${order.channelOrderId}'"
        }
        assert(order.channelOrderNumber == "#1001") {
            "Expected order number '#1001' but got '${order.channelOrderNumber}'"
        }
        assert(order.customerEmail == "customer@example.com") {
            "Expected email 'customer@example.com' but got '${order.customerEmail}'"
        }
        assert(order.currencyCode == "USD") {
            "Expected currency 'USD' but got '${order.currencyCode}'"
        }
        assert(order.channelName == "shopify") {
            "Expected channel name 'shopify' but got '${order.channelName}'"
        }
    }

    @Test
    fun `parse extracts correct number of line items`() {
        val order = adapter.parse(loadPayload())

        assert(order.lineItems.size == 2) {
            "Expected 2 line items but got ${order.lineItems.size}"
        }
    }

    @Test
    fun `parse extracts first line item fields`() {
        val order = adapter.parse(loadPayload())
        val item = order.lineItems[0]

        assert(item.externalProductId == "7513594") {
            "Expected product ID '7513594' but got '${item.externalProductId}'"
        }
        assert(item.externalVariantId == "34505432") {
            "Expected variant ID '34505432' but got '${item.externalVariantId}'"
        }
        assert(item.quantity == 2) {
            "Expected quantity 2 but got ${item.quantity}"
        }
        assert(item.unitPrice.compareTo(BigDecimal("29.99")) == 0) {
            "Expected unit price 29.99 but got ${item.unitPrice}"
        }
        assert(item.title == "Premium Widget") {
            "Expected title 'Premium Widget' but got '${item.title}'"
        }
    }

    @Test
    fun `parse extracts second line item fields`() {
        val order = adapter.parse(loadPayload())
        val item = order.lineItems[1]

        assert(item.externalProductId == "8471210") {
            "Expected product ID '8471210' but got '${item.externalProductId}'"
        }
        assert(item.externalVariantId == "44892716") {
            "Expected variant ID '44892716' but got '${item.externalVariantId}'"
        }
        assert(item.quantity == 1) {
            "Expected quantity 1 but got ${item.quantity}"
        }
        assert(item.unitPrice.compareTo(BigDecimal("49.99")) == 0) {
            "Expected unit price 49.99 but got ${item.unitPrice}"
        }
        assert(item.title == "Deluxe Gadget") {
            "Expected title 'Deluxe Gadget' but got '${item.title}'"
        }
    }

    @Test
    fun `parse falls back to customer email when top-level email missing`() {
        val payload = """
        {
            "id": 12345,
            "name": "#1002",
            "currency": "USD",
            "customer": {
                "id": 99999,
                "email": "fallback@example.com"
            },
            "line_items": []
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assert(order.customerEmail == "fallback@example.com") {
            "Expected fallback email 'fallback@example.com' but got '${order.customerEmail}'"
        }
    }

    @Test
    fun `parse generates deterministic fallback email when no email present`() {
        val payload = """
        {
            "id": 12345,
            "name": "#1003",
            "currency": "USD",
            "customer": {
                "id": 77777
            },
            "line_items": []
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assert(order.customerEmail == "unknown-77777@noemail.shopify") {
            "Expected fallback email 'unknown-77777@noemail.shopify' but got '${order.customerEmail}'"
        }
    }

    @Test
    fun `parse handles line item with null variant_id`() {
        val payload = """
        {
            "id": 12345,
            "name": "#1004",
            "email": "test@example.com",
            "currency": "USD",
            "customer": { "id": 1, "email": "test@example.com" },
            "line_items": [
                {
                    "id": 111,
                    "product_id": 555,
                    "variant_id": null,
                    "title": "No Variant Product",
                    "quantity": 3,
                    "price": "19.99"
                }
            ]
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assert(order.lineItems.size == 1) { "Expected 1 line item" }
        assert(order.lineItems[0].externalVariantId == null) {
            "Expected null variant ID but got '${order.lineItems[0].externalVariantId}'"
        }
        assert(order.lineItems[0].externalProductId == "555") {
            "Expected product ID '555' but got '${order.lineItems[0].externalProductId}'"
        }
        assert(order.lineItems[0].quantity == 3) {
            "Expected quantity 3 but got ${order.lineItems[0].quantity}"
        }
    }

    @Test
    fun `parse throws when order ID is missing`() {
        val payload = """
        {
            "name": "#1005",
            "currency": "USD",
            "customer": { "id": 1, "email": "test@example.com" },
            "line_items": []
        }
        """.trimIndent()

        assertThrows<IllegalStateException> {
            adapter.parse(payload)
        }
    }

    @Test
    fun `channelName returns shopify`() {
        assert(adapter.channelName() == "shopify") {
            "Expected 'shopify' but got '${adapter.channelName()}'"
        }
    }

    // --- Task 6.3: Shipping Address Tests ---

    @Test
    fun `full shipping address extracted correctly with all 11 fields`() {
        val payload = """
        {
            "id": 99001,
            "name": "#2001",
            "email": "shipping@example.com",
            "currency": "USD",
            "customer": { "id": 1, "email": "shipping@example.com" },
            "line_items": [],
            "shipping_address": {
                "first_name": "Jane",
                "last_name": "Smith",
                "address1": "456 Oak Ave",
                "address2": "Suite 200",
                "city": "Portland",
                "province": "Oregon",
                "province_code": "OR",
                "country": "United States",
                "country_code": "US",
                "zip": "97201",
                "phone": "+1-503-555-0199"
            }
        }
        """.trimIndent()

        val order = adapter.parse(payload)
        val addr = order.shippingAddress

        assertNotNull(addr, "shippingAddress should not be null")
        assertEquals("Jane", addr!!.firstName)
        assertEquals("Smith", addr.lastName)
        assertEquals("456 Oak Ave", addr.address1)
        assertEquals("Suite 200", addr.address2)
        assertEquals("Portland", addr.city)
        assertEquals("Oregon", addr.province)
        assertEquals("OR", addr.provinceCode)
        assertEquals("United States", addr.country)
        assertEquals("US", addr.countryCode)
        assertEquals("97201", addr.zip)
        assertEquals("+1-503-555-0199", addr.phone)
    }

    @Test
    fun `JSON null shipping address fields return Kotlin null NOT string null -- PR 39 bug regression`() {
        val payload = """
        {
            "id": 99002,
            "name": "#2002",
            "email": "nulltest@example.com",
            "currency": "USD",
            "customer": { "id": 2, "email": "nulltest@example.com" },
            "line_items": [],
            "shipping_address": {
                "first_name": "Bob",
                "last_name": null,
                "address1": "789 Pine Rd",
                "address2": null,
                "city": "Seattle",
                "province": null,
                "province_code": "WA",
                "country": "United States",
                "country_code": "US",
                "zip": "98101",
                "phone": null
            }
        }
        """.trimIndent()

        val order = adapter.parse(payload)
        val addr = order.shippingAddress

        assertNotNull(addr, "shippingAddress should not be null")

        // CRITICAL: These must be Kotlin null, NOT the string "null"
        assertNull(addr!!.lastName, "lastName should be Kotlin null for JSON null")
        assertNotEquals("null", addr.lastName, "lastName must NOT be the string 'null'")

        assertNull(addr.address2, "address2 should be Kotlin null for JSON null")
        assertNotEquals("null", addr.address2, "address2 must NOT be the string 'null'")

        assertNull(addr.province, "province should be Kotlin null for JSON null")
        assertNotEquals("null", addr.province, "province must NOT be the string 'null'")

        assertNull(addr.phone, "phone should be Kotlin null for JSON null")
        assertNotEquals("null", addr.phone, "phone must NOT be the string 'null'")

        // Present fields still have correct values
        assertEquals("Bob", addr.firstName)
        assertEquals("789 Pine Rd", addr.address1)
        assertEquals("Seattle", addr.city)
        assertEquals("WA", addr.provinceCode)
        assertEquals("United States", addr.country)
        assertEquals("US", addr.countryCode)
        assertEquals("98101", addr.zip)
    }

    @Test
    fun `missing shipping_address node returns null shippingAddress`() {
        val payload = """
        {
            "id": 99003,
            "name": "#2003",
            "email": "noaddr@example.com",
            "currency": "USD",
            "customer": { "id": 3, "email": "noaddr@example.com" },
            "line_items": []
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assertNull(order.shippingAddress, "shippingAddress should be null when shipping_address key is missing")
    }

    @Test
    fun `shipping_address is JSON null returns null shippingAddress`() {
        val payload = """
        {
            "id": 99004,
            "name": "#2004",
            "email": "jsonnull@example.com",
            "currency": "USD",
            "customer": { "id": 4, "email": "jsonnull@example.com" },
            "line_items": [],
            "shipping_address": null
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assertNull(order.shippingAddress, "shippingAddress should be null when shipping_address is JSON null")
    }

    @Test
    fun `mixed null and present shipping fields correctly mapped`() {
        val payload = """
        {
            "id": 99005,
            "name": "#2005",
            "email": "mixed@example.com",
            "currency": "USD",
            "customer": { "id": 5, "email": "mixed@example.com" },
            "line_items": [],
            "shipping_address": {
                "first_name": "Alice",
                "last_name": null,
                "address1": "100 Broadway",
                "address2": null,
                "city": "New York",
                "province": null,
                "province_code": null,
                "country": "United States",
                "country_code": "US",
                "zip": "10001",
                "phone": "+1-212-555-0100"
            }
        }
        """.trimIndent()

        val order = adapter.parse(payload)
        val addr = order.shippingAddress

        assertNotNull(addr, "shippingAddress should not be null")

        // Present fields
        assertEquals("Alice", addr!!.firstName)
        assertEquals("100 Broadway", addr.address1)
        assertEquals("New York", addr.city)
        assertEquals("United States", addr.country)
        assertEquals("US", addr.countryCode)
        assertEquals("10001", addr.zip)
        assertEquals("+1-212-555-0100", addr.phone)

        // Null fields — must be Kotlin null, NOT string "null"
        assertNull(addr.lastName, "lastName should be Kotlin null")
        assertNotEquals("null", addr.lastName)

        assertNull(addr.address2, "address2 should be Kotlin null")
        assertNotEquals("null", addr.address2)

        assertNull(addr.province, "province should be Kotlin null")
        assertNotEquals("null", addr.province)

        assertNull(addr.provinceCode, "provinceCode should be Kotlin null")
        assertNotEquals("null", addr.provinceCode)
    }
}
