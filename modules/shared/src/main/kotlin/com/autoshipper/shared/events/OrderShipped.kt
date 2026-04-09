package com.autoshipper.shared.events

import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class OrderShipped(
    val orderId: OrderId,
    val skuId: SkuId,
    val trackingNumber: String,
    val carrier: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
