package com.autoshipper.portfolio.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "portfolio")
data class PortfolioConfig(
    val killWindowDays: Int = 30,
    val kpiCacheTtlMinutes: Long = 5,
    val autoTerminateEnabled: Boolean = false
)

@Configuration
@EnableConfigurationProperties(PortfolioConfig::class)
class PortfolioConfigProperties
