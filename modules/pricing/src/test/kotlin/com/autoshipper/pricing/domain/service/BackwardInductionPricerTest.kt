package com.autoshipper.pricing.domain.service

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackwardInductionPricerTest {

    private lateinit var pricer: BackwardInductionPricer
    private val marginFloor = Percentage.of(30)

    private fun usd(amount: Double) = Money.of(amount, Currency.USD)

    @BeforeEach
    fun setUp() {
        pricer = BackwardInductionPricer()
    }

    @Test
    fun `returns WTP ceiling when margin exceeds floor`() {
        // fullyBurdened = 40, WTP = 100 → margin = 60% > 30%
        val result = pricer.compute(usd(100.0), usd(40.0), marginFloor)

        assertNotNull(result)
        assertEquals(usd(100.0), result)
    }

    @Test
    fun `returns WTP ceiling when margin exactly at floor`() {
        // fullyBurdened = 70, WTP = 100 → margin = 30% == 30%
        val result = pricer.compute(usd(100.0), usd(70.0), marginFloor)

        assertNotNull(result)
        assertEquals(usd(100.0), result)
    }

    @Test
    fun `returns null when margin below floor`() {
        // fullyBurdened = 75, WTP = 100 → margin = 25% < 30%
        val result = pricer.compute(usd(100.0), usd(75.0), marginFloor)

        assertNull(result)
    }

    @Test
    fun `returns null when cost exceeds WTP ceiling`() {
        // fullyBurdened = 120, WTP = 100 → negative margin
        val result = pricer.compute(usd(100.0), usd(120.0), marginFloor)

        assertNull(result)
    }

    @Test
    fun `computes minimum viable price correctly`() {
        // fullyBurdened = 70, marginFloor = 30%
        // minPrice = 70 / (1 - 0.30) = 70 / 0.70 = 100.0
        val result = pricer.computeMinimumViablePrice(usd(70.0), marginFloor)

        assertEquals(usd(100.0), result)
    }

    @Test
    fun `computes minimum viable price for higher cost`() {
        // fullyBurdened = 50, marginFloor = 30%
        // minPrice = 50 / 0.70 = 71.4286
        val result = pricer.computeMinimumViablePrice(usd(50.0), marginFloor)

        assertEquals(Money.of(71.4286, Currency.USD), result)
    }
}
