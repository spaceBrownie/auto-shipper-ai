package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import java.time.Instant

data class CostEnvelopeVerified(
    val skuId: SkuId,
    val fullyBurdenedCost: Money,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
