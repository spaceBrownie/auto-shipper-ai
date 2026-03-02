package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import java.time.Instant

class CostEnvelopeExpiredException(
    skuId: SkuId,
    verifiedAt: Instant,
    ttlHours: Long
) : RuntimeException(
    "Cost envelope for SKU $skuId expired. Verified at $verifiedAt, TTL was ${ttlHours}h. Re-verify before listing."
)
