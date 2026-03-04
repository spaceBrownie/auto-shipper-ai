package com.autoshipper.pricing.domain.service

import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class BackwardInductionPricer {

    /**
     * Computes the launch price using backward induction:
     * WTP ceiling → subtract fully burdened cost → check margin floor → return price or null.
     *
     * Returns the WTP ceiling as the price if it satisfies the margin floor.
     * Returns null if no price at or below the WTP ceiling can maintain the required margin.
     */
    fun compute(wtpCeiling: Money, fullyBurdenedCost: Money, marginFloor: Percentage): Money? {
        if (fullyBurdenedCost.normalizedAmount >= wtpCeiling.normalizedAmount) {
            return null // Cost exceeds or equals WTP — negative or zero margin
        }
        val margin = fullyBurdenedCost.marginAgainst(wtpCeiling)

        return if (margin.value >= marginFloor.value) {
            wtpCeiling
        } else {
            null
        }
    }

    /**
     * Computes the minimum price that achieves exactly the given margin floor.
     * price = fullyBurdenedCost / (1 - marginFloor/100)
     */
    fun computeMinimumViablePrice(fullyBurdenedCost: Money, marginFloor: Percentage): Money {
        val divisor = BigDecimal.ONE.subtract(marginFloor.toDecimalFraction())
        val minPrice = fullyBurdenedCost.normalizedAmount
            .divide(divisor, 4, RoundingMode.HALF_UP)
        return Money.of(minPrice, fullyBurdenedCost.currency)
    }
}
