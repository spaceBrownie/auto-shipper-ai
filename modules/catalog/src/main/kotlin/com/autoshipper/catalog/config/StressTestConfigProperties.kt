package com.autoshipper.catalog.config

import com.autoshipper.catalog.domain.StressTestConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(StressTestConfig::class)
class StressTestConfigProperties
