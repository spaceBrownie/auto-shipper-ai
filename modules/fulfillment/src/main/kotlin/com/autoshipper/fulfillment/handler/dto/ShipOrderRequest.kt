package com.autoshipper.fulfillment.handler.dto

data class ShipOrderRequest(
    val trackingNumber: String,
    val carrier: String
)
