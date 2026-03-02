package com.autoshipper.shared.money

import java.math.BigDecimal
import java.math.RoundingMode

class CurrencyMismatchException(from: Currency, to: Currency) :
    IllegalArgumentException("Cannot operate on $from and $to amounts together")

data class Money(val amount: BigDecimal, val currency: Currency) {

    constructor(amount: Double, currency: Currency) : this(BigDecimal.valueOf(amount), currency)

    init {
        // Enforce scale=4 on all Money instances
        // We reassign through the primary constructor path via copy; here we validate post-init.
    }

    // Canonical form: always scale 4
    val normalizedAmount: BigDecimal = amount.setScale(4, RoundingMode.HALF_UP)

    companion object {
        fun of(amount: BigDecimal, currency: Currency): Money =
            Money(amount.setScale(4, RoundingMode.HALF_UP), currency)

        fun of(amount: Double, currency: Currency): Money =
            Money(BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP), currency)
    }

    operator fun plus(other: Money): Money {
        if (currency != other.currency) throw CurrencyMismatchException(currency, other.currency)
        return Money(normalizedAmount.add(other.normalizedAmount), currency)
    }

    operator fun minus(other: Money): Money {
        if (currency != other.currency) throw CurrencyMismatchException(currency, other.currency)
        return Money(normalizedAmount.subtract(other.normalizedAmount), currency)
    }

    operator fun times(factor: BigDecimal): Money =
        Money(normalizedAmount.multiply(factor).setScale(4, RoundingMode.HALF_UP), currency)

    /**
     * Calculates gross margin percentage: (revenue - cost) / revenue * 100
     * where `this` is the cost and [revenue] is the selling price.
     */
    fun marginAgainst(revenue: Money): Percentage {
        if (currency != revenue.currency) throw CurrencyMismatchException(currency, revenue.currency)
        require(revenue.normalizedAmount.compareTo(BigDecimal.ZERO) != 0) {
            "Revenue must not be zero when calculating margin"
        }
        val margin = revenue.normalizedAmount
            .subtract(normalizedAmount)
            .divide(revenue.normalizedAmount, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
        return Percentage.of(margin)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return normalizedAmount.compareTo(other.normalizedAmount) == 0 && currency == other.currency
    }

    override fun hashCode(): Int = 31 * normalizedAmount.stripTrailingZeros().hashCode() + currency.hashCode()

    override fun toString(): String = "$normalizedAmount $currency"
}
