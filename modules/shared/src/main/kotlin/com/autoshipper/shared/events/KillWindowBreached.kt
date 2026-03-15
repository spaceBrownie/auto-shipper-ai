package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.math.BigDecimal
import java.time.Instant

data class KillWindowBreached(
    val skuId: SkuId,
    val daysNegative: Int,
    val avgNetMargin: BigDecimal,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
