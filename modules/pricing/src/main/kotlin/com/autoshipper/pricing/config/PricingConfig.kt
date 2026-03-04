package com.autoshipper.pricing.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "pricing")
data class PricingConfig(
    val marginFloorPercent: BigDecimal = BigDecimal("30"),
    val conversionThresholdPercent: BigDecimal = BigDecimal("15"),
    val maxPriceMultiplier: BigDecimal = BigDecimal("2.0")
)
