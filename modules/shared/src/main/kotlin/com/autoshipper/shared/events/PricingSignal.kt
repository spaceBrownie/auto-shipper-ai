package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import java.time.Instant

sealed class PricingSignal : DomainEvent {

    data class ShippingCostChanged(
        val skuId: SkuId,
        val delta: Money,
        override val occurredAt: Instant = Instant.now()
    ) : PricingSignal()

    data class VendorCostChanged(
        val skuId: SkuId,
        val delta: Money,
        override val occurredAt: Instant = Instant.now()
    ) : PricingSignal()

    data class CacChanged(
        val skuId: SkuId,
        val delta: Money,
        override val occurredAt: Instant = Instant.now()
    ) : PricingSignal()

    data class PlatformFeeChanged(
        val skuId: SkuId,
        val delta: Money,
        override val occurredAt: Instant = Instant.now()
    ) : PricingSignal()
}
