package com.autoshipper.fulfillment.handler.dto

data class TrackingResponse(
    val orderId: String,
    val trackingNumber: String?,
    val carrier: String?,
    val estimatedDelivery: String?,
    val lastKnownLocation: String?,
    val delayDetected: Boolean,
    val status: String
)
