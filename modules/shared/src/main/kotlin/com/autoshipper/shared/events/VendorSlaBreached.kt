package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import com.autoshipper.shared.money.Percentage
import java.time.Instant

data class VendorSlaBreached(
    val vendorId: VendorId,
    val skuIds: List<SkuId>,
    val breachRate: Percentage,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
