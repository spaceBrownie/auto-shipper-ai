package com.autoshipper.capital.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@ConfigurationProperties(prefix = "capital")
data class CapitalConfig(
    val netMarginFloorPercent: BigDecimal = BigDecimal("30"),
    val sustainedDays: Int = 7,
    val refundRateMaxPercent: BigDecimal = BigDecimal("5"),
    val chargebackRateMaxPercent: BigDecimal = BigDecimal("2"),
    val cacVarianceMaxPercent: BigDecimal = BigDecimal("15"),
    val cacVarianceDays: Int = 14,
    val reserveRateMinPercent: BigDecimal = BigDecimal("10"),
    val reserveRateMaxPercent: BigDecimal = BigDecimal("15")
)

@Configuration
@EnableConfigurationProperties(CapitalConfig::class)
class CapitalConfigProperties
