package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

data class ShutdownRuleTriggered(
    val skuId: SkuId,
    val rule: String,
    val conditionValue: String,
    val action: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
