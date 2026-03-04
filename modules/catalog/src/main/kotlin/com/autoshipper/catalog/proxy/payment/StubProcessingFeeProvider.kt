package com.autoshipper.catalog.proxy.payment

import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
@Profile("local")
class StubProcessingFeeProvider : ProcessingFeeProvider {
    companion object {
        private val STRIPE_PERCENTAGE_RATE = BigDecimal("0.029")
        private val STRIPE_FIXED_FEE = BigDecimal("0.30")
    }

    override fun getFee(estimatedOrderValue: Money): Money {
        val percentageFee = estimatedOrderValue.normalizedAmount
            .multiply(STRIPE_PERCENTAGE_RATE)
            .setScale(4, RoundingMode.HALF_UP)
        val totalFeeAmount = percentageFee
            .add(STRIPE_FIXED_FEE)
            .setScale(4, RoundingMode.HALF_UP)
        return Money.of(totalFeeAmount, estimatedOrderValue.currency)
    }
}
