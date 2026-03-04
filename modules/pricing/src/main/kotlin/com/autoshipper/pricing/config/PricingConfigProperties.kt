package com.autoshipper.pricing.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PricingConfig::class)
class PricingConfigProperties
