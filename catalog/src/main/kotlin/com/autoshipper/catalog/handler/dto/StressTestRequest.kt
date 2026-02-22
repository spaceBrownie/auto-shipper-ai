package com.autoshipper.catalog.handler.dto

import java.math.BigDecimal

data class StressTestRequest(
    val estimatedPriceAmount: BigDecimal,
    val currency: String = "USD"
)
