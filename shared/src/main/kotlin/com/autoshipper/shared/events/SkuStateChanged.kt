package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class SkuStateChanged(
    val skuId: SkuId,
    val fromState: String,
    val toState: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
