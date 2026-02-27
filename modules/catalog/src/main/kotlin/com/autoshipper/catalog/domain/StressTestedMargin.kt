package com.autoshipper.catalog.domain

import com.autoshipper.shared.money.Percentage
import java.math.BigDecimal

@JvmInline
value class StressTestedMargin(val value: Percentage) {
    init {
        require(value.value >= BigDecimal("30")) {
            "Stressed net margin ${value} is below the 30% floor — SKU must be terminated"
        }
    }
}
