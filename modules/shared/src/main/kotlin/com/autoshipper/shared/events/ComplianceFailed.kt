package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class ComplianceFailed(
    val skuId: SkuId,
    val reason: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
