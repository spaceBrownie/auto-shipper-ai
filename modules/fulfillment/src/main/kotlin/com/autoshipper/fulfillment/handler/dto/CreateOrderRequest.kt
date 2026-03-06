package com.autoshipper.fulfillment.handler.dto

data class CreateOrderRequest(
    val skuId: String,
    val vendorId: String,
    val customerId: String,
    val idempotencyKey: String
)
