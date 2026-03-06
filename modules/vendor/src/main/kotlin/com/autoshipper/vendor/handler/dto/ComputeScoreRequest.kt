package com.autoshipper.vendor.handler.dto

import java.math.BigDecimal

data class ComputeScoreRequest(
    val onTimeRate: BigDecimal,
    val defectRate: BigDecimal,
    val avgResponseTimeHours: BigDecimal
)
