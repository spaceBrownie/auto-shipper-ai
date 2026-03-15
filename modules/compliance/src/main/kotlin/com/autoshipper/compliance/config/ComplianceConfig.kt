package com.autoshipper.compliance.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "compliance")
data class ComplianceConfig(
    val llmEnabled: Boolean = false,
    val autoCheckEnabled: Boolean = true,
    val sanctionsListPath: String = "classpath:compliance/sanctions-list.txt",
    val prohibitedCategoriesPath: String = "classpath:compliance/prohibited-categories.txt"
)

@Configuration
@EnableConfigurationProperties(ComplianceConfig::class)
class ComplianceConfigProperties
