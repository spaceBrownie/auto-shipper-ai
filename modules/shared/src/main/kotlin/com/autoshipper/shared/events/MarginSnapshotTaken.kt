package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.math.BigDecimal
import java.time.Instant

data class MarginSnapshotTaken(
    val skuId: SkuId,
    val netMarginPercent: BigDecimal,
    val grossMarginPercent: BigDecimal,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
