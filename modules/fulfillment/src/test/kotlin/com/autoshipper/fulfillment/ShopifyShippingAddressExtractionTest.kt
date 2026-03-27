package com.autoshipper.fulfillment

import com.autoshipper.fulfillment.domain.channel.ShopifyOrderAdapter
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for ShopifyOrderAdapter shipping address extraction.
 *
 * Verifies that the adapter correctly extracts shipping_address fields from
 * Shopify webhook payloads and includes them in the ChannelOrder.
 *
 * Uses Jackson get() (not path()) per CLAUDE.md #15.
 */
class ShopifyShippingAddressExtractionTest {

    private val objectMapper = ObjectMapper()
    private val adapter = ShopifyOrderAdapter(objectMapper)

    /**
     * Test: Verify ShopifyOrderAdapter extracts all shipping address fields.
     */
    @Test
    fun `parse extracts shipping address fields from webhook payload`() {
        val payload = """
        {
            "id": 820982911946154500,
            "name": "#1001",
            "email": "customer@example.com",
            "currency": "USD",
            "customer": {
                "id": 115310627314723950,
                "email": "customer@example.com"
            },
            "line_items": [
                {
                    "id": 866550311766439000,
                    "product_id": 7513594,
                    "variant_id": 34505432,
                    "title": "Premium Widget",
                    "quantity": 1,
                    "price": "29.99"
                }
            ],
            "shipping_address": {
                "name": "John Doe",
                "address1": "123 Main St",
                "address2": "Suite 100",
                "city": "Anytown",
                "province": "California",
                "country": "United States",
                "country_code": "US",
                "zip": "90210",
                "phone": "+1-555-123-4567"
            }
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        val addr = order.shippingAddress
        assertNotNull(addr, "Expected shipping address to be extracted")
        assertEquals("John Doe", addr!!.customerName)
        assertEquals("123 Main St", addr.address1)
        assertEquals("Suite 100", addr.address2)
        assertEquals("Anytown", addr.city)
        assertEquals("California", addr.province)
        assertEquals("United States", addr.country)
        assertEquals("US", addr.countryCode)
        assertEquals("90210", addr.zip)
        assertEquals("+1-555-123-4567", addr.phone)
    }

    /**
     * Test: Verify address1 only (no address2) is handled correctly.
     */
    @Test
    fun `parse handles shipping address without address2`() {
        val payload = """
        {
            "id": 820982911946154501,
            "name": "#1002",
            "email": "customer2@example.com",
            "currency": "USD",
            "customer": {
                "id": 115310627314723951,
                "email": "customer2@example.com"
            },
            "line_items": [
                {
                    "id": 866550311766439001,
                    "product_id": 7513595,
                    "variant_id": 34505433,
                    "title": "Basic Widget",
                    "quantity": 1,
                    "price": "19.99"
                }
            ],
            "shipping_address": {
                "name": "Jane Smith",
                "address1": "456 Oak Ave",
                "city": "Springfield",
                "province": "IL",
                "country": "United States",
                "country_code": "US",
                "zip": "62704"
            }
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assertEquals("820982911946154501", order.channelOrderId)

        val addr = order.shippingAddress
        assertNotNull(addr)
        assertEquals("Jane Smith", addr!!.customerName)
        assertEquals("456 Oak Ave", addr.address1)
        assertNull(addr.address2)
        assertEquals("Springfield", addr.city)
        assertNull(addr.phone)

        // Verify combined address logic: address2 null -> no trailing comma
        val combined = listOfNotNull(addr.address1, addr.address2).joinToString(", ")
        assertEquals("456 Oak Ave", combined)
    }

    /**
     * Test: Verify missing shipping_address node handled gracefully (null).
     */
    @Test
    fun `parse handles missing shipping address gracefully`() {
        val payload = """
        {
            "id": 820982911946154502,
            "name": "#1003",
            "email": "customer3@example.com",
            "currency": "USD",
            "customer": {
                "id": 115310627314723952,
                "email": "customer3@example.com"
            },
            "line_items": [
                {
                    "id": 866550311766439002,
                    "product_id": 7513596,
                    "variant_id": 34505434,
                    "title": "Digital Download",
                    "quantity": 1,
                    "price": "9.99"
                }
            ]
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assertEquals("820982911946154502", order.channelOrderId)
        assertEquals("#1003", order.channelOrderNumber)
        assertEquals("customer3@example.com", order.customerEmail)
        assertEquals(1, order.lineItems.size)
        assertNull(order.shippingAddress)
    }

    /**
     * Test: Verify null shipping_address node handled gracefully.
     */
    @Test
    fun `parse handles explicit null shipping address`() {
        val payload = """
        {
            "id": 820982911946154503,
            "name": "#1004",
            "email": "customer4@example.com",
            "currency": "USD",
            "customer": {
                "id": 115310627314723953,
                "email": "customer4@example.com"
            },
            "line_items": [],
            "shipping_address": null
        }
        """.trimIndent()

        val order = adapter.parse(payload)

        assertEquals("820982911946154503", order.channelOrderId)
        assertNull(order.shippingAddress)
    }

    /**
     * Test: Existing ShopifyOrderAdapter fixture still parses correctly.
     * The fixture has a shipping_address field but does NOT have a 'name' field.
     */
    @Test
    fun `existing webhook fixture parses correctly with shipping address`() {
        val payload = javaClass.classLoader
            .getResource("shopify/orders-create-webhook.json")!!
            .readText()

        val order = adapter.parse(payload)

        assertEquals("820982911946154500", order.channelOrderId)
        assertEquals("#1001", order.channelOrderNumber)
        assertEquals("customer@example.com", order.customerEmail)
        assertEquals(2, order.lineItems.size)

        // The fixture has shipping_address with address1, city, province, country, zip
        // but does NOT have 'name' or 'phone' fields
        val addr = order.shippingAddress
        assertNotNull(addr)
        assertNull(addr!!.customerName, "Fixture has no 'name' field in shipping_address")
        assertEquals("123 Main St", addr.address1)
        assertEquals("Anytown", addr.city)
        assertEquals("CA", addr.province)
        assertEquals("US", addr.country)
        assertEquals("90210", addr.zip)
        assertNull(addr.phone, "Fixture has no 'phone' field in shipping_address")
    }
}
