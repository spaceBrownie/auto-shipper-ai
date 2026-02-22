package com.autoshipper.catalog.domain

data class LaunchReadySku(
    val sku: Sku,
    val envelope: CostEnvelope.Verified,
    val stressTestedMargin: StressTestedMargin
)
