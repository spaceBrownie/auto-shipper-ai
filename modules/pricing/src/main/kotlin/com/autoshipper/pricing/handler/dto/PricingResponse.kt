package com.autoshipper.pricing.handler.dto

import java.math.BigDecimal

data class PricingResponse(
    val skuId: String,
    val currency: String,
    val currentPrice: BigDecimal,
    val currentMarginPercent: BigDecimal,
    val updatedAt: String,
    val history: List<PricingHistoryEntry>
)

data class PricingHistoryEntry(
    val price: BigDecimal,
    val marginPercent: BigDecimal,
    val signalType: String,
    val decisionType: String,
    val decisionReason: String?,
    val recordedAt: String
)
