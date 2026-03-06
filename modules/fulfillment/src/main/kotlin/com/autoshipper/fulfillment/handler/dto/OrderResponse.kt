package com.autoshipper.fulfillment.handler.dto

data class OrderResponse(
    val id: String,
    val skuId: String,
    val vendorId: String,
    val customerId: String,
    val status: String,
    val trackingNumber: String?,
    val carrier: String?,
    val estimatedDelivery: String?,
    val createdAt: String,
    val updatedAt: String
)
