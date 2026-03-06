package com.autoshipper.fulfillment.domain.service

import java.util.UUID

data class CreateOrderCommand(
    val skuId: UUID,
    val vendorId: UUID,
    val customerId: UUID,
    val idempotencyKey: String
)
