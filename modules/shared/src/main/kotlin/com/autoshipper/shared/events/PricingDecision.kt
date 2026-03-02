package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import java.time.Instant

sealed class PricingDecision : DomainEvent {

    data class Adjusted(
        val skuId: SkuId,
        val newPrice: Money,
        override val occurredAt: Instant = Instant.now()
    ) : PricingDecision()

    data class PauseRequired(
        val skuId: SkuId,
        val reason: String,
        override val occurredAt: Instant = Instant.now()
    ) : PricingDecision()

    data class TerminateRequired(
        val skuId: SkuId,
        val reason: String,
        override val occurredAt: Instant = Instant.now()
    ) : PricingDecision()
}
