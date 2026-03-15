package com.autoshipper.shared.events

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import java.time.Instant

data class SkuReadyForComplianceCheck(
    val skuId: SkuId,
    val productName: String,
    val productDescription: String,
    val category: String,
    val vendorId: VendorId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
