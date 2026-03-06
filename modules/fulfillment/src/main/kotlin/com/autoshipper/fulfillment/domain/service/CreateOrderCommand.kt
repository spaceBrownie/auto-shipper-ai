package com.autoshipper.fulfillment.domain.service

import com.autoshipper.shared.money.Money
import java.util.UUID

data class CreateOrderCommand(
    val skuId: UUID,
    val vendorId: UUID,
    val customerId: UUID,
    val totalAmount: Money,
    val paymentIntentId: String,
    val idempotencyKey: String
)
