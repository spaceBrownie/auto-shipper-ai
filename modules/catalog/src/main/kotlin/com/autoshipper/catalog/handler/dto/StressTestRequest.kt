package com.autoshipper.catalog.handler.dto

import jakarta.validation.constraints.DecimalMin
import java.math.BigDecimal

data class StressTestRequest(
    @field:DecimalMin(value = "0.01", message = "Estimated price must be at least 0.01")
    val estimatedPriceAmount: BigDecimal,
    val currency: String = "USD"
)
