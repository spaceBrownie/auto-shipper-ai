package com.autoshipper.shared

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.CurrencyMismatchException
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `addition of same currency amounts`() {
        val a = Money.of(10.00, Currency.USD)
        val b = Money.of(5.00, Currency.USD)
        val result = a + b
        assertEquals(Money.of(15.00, Currency.USD), result)
    }

    @Test
    fun `subtraction of same currency amounts`() {
        val a = Money.of(10.00, Currency.USD)
        val b = Money.of(3.00, Currency.USD)
        val result = a - b
        assertEquals(Money.of(7.00, Currency.USD), result)
    }

    @Test
    fun `cross-currency addition throws CurrencyMismatchException`() {
        val usd = Money.of(10.00, Currency.USD)
        val eur = Money.of(10.00, Currency.EUR)
        assertThrows<CurrencyMismatchException> { usd + eur }
    }

    @Test
    fun `cross-currency subtraction throws CurrencyMismatchException`() {
        val usd = Money.of(10.00, Currency.USD)
        val gbp = Money.of(5.00, Currency.GBP)
        assertThrows<CurrencyMismatchException> { usd - gbp }
    }

    @Test
    fun `marginAgainst calculates correct gross margin percentage`() {
        val cost = Money.of(4.00, Currency.USD)
        val revenue = Money.of(10.00, Currency.USD)
        val margin = cost.marginAgainst(revenue)
        // (10 - 4) / 10 * 100 = 60.00%
        assertEquals(Percentage.of(BigDecimal("60.0000")), margin)
    }

    @Test
    fun `marginAgainst with zero revenue throws IllegalArgumentException`() {
        val cost = Money.of(4.00, Currency.USD)
        val zeroRevenue = Money.of(0.00, Currency.USD)
        assertThrows<IllegalArgumentException> { cost.marginAgainst(zeroRevenue) }
    }

    @Test
    fun `marginAgainst cross-currency throws CurrencyMismatchException`() {
        val cost = Money.of(4.00, Currency.USD)
        val revenue = Money.of(10.00, Currency.EUR)
        assertThrows<CurrencyMismatchException> { cost.marginAgainst(revenue) }
    }

    @Test
    fun `amount scale is always 4 decimal places`() {
        val money = Money.of(BigDecimal("10"), Currency.USD)
        assertEquals(4, money.normalizedAmount.scale())
    }

    @Test
    fun `Money constructed with raw BigDecimal enforces scale 4`() {
        val money = Money(BigDecimal("7.5"), Currency.GBP)
        assertEquals(4, money.normalizedAmount.scale())
        assertTrue(money.normalizedAmount.compareTo(BigDecimal("7.5000")) == 0)
    }

    @Test
    fun `times multiplies amount by factor`() {
        val money = Money.of(10.00, Currency.USD)
        val result = money * BigDecimal("2")
        assertEquals(Money.of(20.00, Currency.USD), result)
    }
}
