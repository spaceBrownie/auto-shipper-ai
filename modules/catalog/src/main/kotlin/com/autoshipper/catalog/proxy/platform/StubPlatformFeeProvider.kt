package com.autoshipper.catalog.proxy.platform

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@Profile("local")
class StubPlatformFeeProvider : PlatformFeeProvider {
    companion object {
        private val BASIC_PLAN_RATE = BigDecimal("0.020")
        private val DEFAULT_ORDER_VALUE = BigDecimal("100.00")
    }

    override fun getFee(): Money {
        val feeAmount = DEFAULT_ORDER_VALUE.multiply(BASIC_PLAN_RATE)
        return Money.of(feeAmount, Currency.USD)
    }
}
