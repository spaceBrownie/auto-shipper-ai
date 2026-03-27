package com.autoshipper.shared.events

import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class OrderConfirmed(
    val orderId: OrderId,
    val skuId: SkuId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
