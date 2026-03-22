package com.autoshipper.fulfillment.handler.dto

data class OrderResponse(
    val id: String,
    val skuId: String,
    val vendorId: String,
    val customerId: String,
    val totalAmount: String,
    val totalCurrency: String,
    val status: String,
    val trackingNumber: String?,
    val carrier: String?,
    val estimatedDelivery: String?,
    val channel: String? = null,
    val channelOrderId: String? = null,
    val channelOrderNumber: String? = null,
    val createdAt: String,
    val updatedAt: String
)
