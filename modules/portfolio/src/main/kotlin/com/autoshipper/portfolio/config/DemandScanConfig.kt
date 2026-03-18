package com.autoshipper.portfolio.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "demand-scan")
data class DemandScanConfig(
    val enabled: Boolean = true,
    val cooldownHours: Int = 20,
    val validationWindowDays: Int = 30,
    val scoringWeights: ScoringWeights = ScoringWeights(),
    val scoringThreshold: Double = 0.6,
    val dedupSimilarityThreshold: Double = 0.7
)

data class ScoringWeights(
    val demand: Double = 0.4,
    val marginPotential: Double = 0.35,
    val competition: Double = 0.25
)

@Configuration
@EnableConfigurationProperties(DemandScanConfig::class)
class DemandScanConfigProperties
