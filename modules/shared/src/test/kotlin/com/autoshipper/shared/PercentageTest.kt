package com.autoshipper.shared

import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PercentageTest {

    @Test
    fun `Percentage of 50 is valid`() {
        val p = Percentage.of(50)
        assertTrue(p.value.compareTo(BigDecimal(50)) == 0)
    }

    @Test
    fun `Percentage of 0 is valid boundary`() {
        val p = Percentage.of(0)
        assertTrue(p.value.compareTo(BigDecimal.ZERO) == 0)
    }

    @Test
    fun `Percentage of 100 is valid boundary`() {
        val p = Percentage.of(100)
        assertTrue(p.value.compareTo(BigDecimal(100)) == 0)
    }

    @Test
    fun `Percentage of negative value throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            Percentage(BigDecimal("-1"))
        }
    }

    @Test
    fun `Percentage of 101 throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            Percentage(BigDecimal("101"))
        }
    }

    @Test
    fun `toDecimalFraction of 25 equals 0_2500`() {
        val p = Percentage.of(25)
        val fraction = p.toDecimalFraction()
        assertEquals(0, fraction.compareTo(BigDecimal("0.2500")))
        assertEquals(4, fraction.scale())
    }

    @Test
    fun `plus adds two percentages`() {
        val a = Percentage.of(30)
        val b = Percentage.of(20)
        val result = a + b
        assertTrue(result.value.compareTo(BigDecimal(50)) == 0)
    }

    @Test
    fun `minus subtracts two percentages`() {
        val a = Percentage.of(70)
        val b = Percentage.of(20)
        val result = a - b
        assertTrue(result.value.compareTo(BigDecimal(50)) == 0)
    }

    @Test
    fun `times multiplies percentage by factor`() {
        val p = Percentage.of(BigDecimal("20"))
        val result = p * BigDecimal("2")
        assertTrue(result.value.compareTo(BigDecimal("40.0000")) == 0)
    }

    @Test
    fun `toDecimalFraction of 100 equals 1_0000`() {
        val p = Percentage.of(100)
        val fraction = p.toDecimalFraction()
        assertEquals(0, fraction.compareTo(BigDecimal("1.0000")))
    }
}
