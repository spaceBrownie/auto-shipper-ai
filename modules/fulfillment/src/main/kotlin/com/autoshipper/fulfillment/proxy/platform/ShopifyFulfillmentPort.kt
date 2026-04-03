package com.autoshipper.fulfillment.proxy.platform

/**
 * Port for creating fulfillments on Shopify when an order transitions to SHIPPED.
 * Real implementation calls the Shopify GraphQL Admin API; stub returns true for local dev.
 */
interface ShopifyFulfillmentPort {
    /**
     * Creates a fulfillment on Shopify for the given order.
     *
     * @param shopifyOrderGid The Shopify order GID (e.g. "gid://shopify/Order/12345")
     * @param trackingNumber The carrier tracking number
     * @param carrier The carrier name (e.g. "UPS", "FedEx")
     * @return true if the fulfillment was created successfully, false on business-level errors
     * @throws org.springframework.web.client.RestClientException on HTTP-level failures (let Resilience4j retry)
     */
    fun createFulfillment(shopifyOrderGid: String, trackingNumber: String, carrier: String): Boolean
}
