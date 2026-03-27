package com.autoshipper.fulfillment.handler.dto

data class CreateOrderRequest(
    val skuId: String,
    val vendorId: String,
    val customerId: String,
    val totalAmount: String,
    val totalCurrency: String,
    val paymentIntentId: String,
    val idempotencyKey: String,
    val quantity: Int = 1
)
