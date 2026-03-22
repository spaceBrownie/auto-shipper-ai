package com.autoshipper.fulfillment.domain.channel

import com.fasterxml.jackson.databind.ObjectMapper
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
}
