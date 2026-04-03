package com.autoshipper.fulfillment.proxy.platform

/**
 * Port for creating fulfillments on Shopify when an order transitions to SHIPPED.
 * Real implementation calls the Shopify GraphQL Admin API; stub returns true for local dev.
 */
interface ShopifyFulfillmentPort {
    /**
     * Creates a fulfillment on Shopify for the given order.
     *
     * @param shopifyOrderId The Shopify order numeric ID (e.g. "820982911946154500").
     *        The adapter converts this to a GID, queries fulfillment orders, and creates the fulfillment.
     * @param trackingNumber The carrier tracking number
     * @param carrier The carrier name (e.g. "UPS", "FedEx")
     * @return true if the fulfillment was created successfully, false on business-level errors
     * @throws org.springframework.web.client.RestClientException on HTTP-level failures (let Resilience4j retry)
     */
    fun createFulfillment(shopifyOrderId: String, trackingNumber: String, carrier: String): Boolean
}
