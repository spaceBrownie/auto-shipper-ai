package com.autoshipper.fulfillment.domain

enum class OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    REFUNDED,
    RETURNED
}
