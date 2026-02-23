package com.autoshipper.catalog.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class PackageDimensions(
    val lengthCm: BigDecimal,
    val widthCm: BigDecimal,
    val heightCm: BigDecimal,
    val weightKg: BigDecimal
) {
    init {
        require(lengthCm > BigDecimal.ZERO) { "Length must be positive" }
        require(widthCm > BigDecimal.ZERO) { "Width must be positive" }
        require(heightCm > BigDecimal.ZERO) { "Height must be positive" }
        require(weightKg > BigDecimal.ZERO) { "Weight must be positive" }
    }

    fun dimWeight(divisorCm3PerKg: BigDecimal): BigDecimal =
        lengthCm.multiply(widthCm).multiply(heightCm)
            .divide(divisorCm3PerKg, 4, RoundingMode.HALF_UP)

    fun billableWeight(divisorCm3PerKg: BigDecimal): BigDecimal =
        dimWeight(divisorCm3PerKg).max(weightKg)
}
