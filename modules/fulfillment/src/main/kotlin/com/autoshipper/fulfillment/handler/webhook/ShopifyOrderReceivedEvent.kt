package com.autoshipper.fulfillment.handler.webhook

data class ShopifyOrderReceivedEvent(
    val rawPayload: String,
    val shopifyEventId: String
)
