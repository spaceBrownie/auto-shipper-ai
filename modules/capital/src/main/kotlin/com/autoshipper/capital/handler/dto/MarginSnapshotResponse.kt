package com.autoshipper.capital.handler.dto

import java.math.BigDecimal

data class MarginSnapshotResponse(
    val snapshotDate: String,
    val grossMarginPercent: BigDecimal,
    val netMarginPercent: BigDecimal,
    val refundRate: BigDecimal,
    val chargebackRate: BigDecimal
)
