package com.autoshipper.fulfillment.handler.webhook

data class CjTrackingReceivedEvent(
    val rawPayload: String,
    val dedupKey: String
)
