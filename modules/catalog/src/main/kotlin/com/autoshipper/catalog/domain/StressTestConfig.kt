package com.autoshipper.catalog.domain

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "stress-test")
data class StressTestConfig(
    val shippingMultiplier: BigDecimal = BigDecimal("2.0"),
    val cacIncreasePercent: BigDecimal = BigDecimal("15"),
    val supplierIncreasePercent: BigDecimal = BigDecimal("10"),
    val refundRatePercent: BigDecimal = BigDecimal("5"),
    val chargebackRatePercent: BigDecimal = BigDecimal("2"),
    val grossMarginFloorPercent: BigDecimal = BigDecimal("50"),
    val netMarginFloorPercent: BigDecimal = BigDecimal("30")
)
