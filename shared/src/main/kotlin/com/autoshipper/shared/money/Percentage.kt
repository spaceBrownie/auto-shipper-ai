package com.autoshipper.shared.money

import java.math.BigDecimal
import java.math.RoundingMode

@JvmInline
value class Percentage(val value: BigDecimal) {

    init {
        require(value >= BigDecimal.ZERO && value <= BigDecimal(100)) {
            "Percentage must be between 0 and 100, got $value"
        }
    }

    companion object {
        fun of(value: BigDecimal): Percentage = Percentage(value)
        fun of(value: Double): Percentage = Percentage(BigDecimal.valueOf(value))
        fun of(value: Int): Percentage = Percentage(BigDecimal(value))
    }

    fun toDecimalFraction(): BigDecimal =
        value.divide(BigDecimal(100), 4, RoundingMode.HALF_UP)

    operator fun plus(other: Percentage): Percentage =
        Percentage(value.add(other.value))

    operator fun minus(other: Percentage): Percentage =
        Percentage(value.subtract(other.value))

    operator fun times(factor: BigDecimal): Percentage =
        Percentage(value.multiply(factor).setScale(4, RoundingMode.HALF_UP))

    override fun toString(): String = "$value%"
}
