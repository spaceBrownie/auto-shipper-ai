package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class ComplianceCleared(
    val skuId: SkuId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
