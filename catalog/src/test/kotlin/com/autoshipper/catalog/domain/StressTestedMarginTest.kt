package com.autoshipper.catalog.domain

import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StressTestedMarginTest {

    @Test
    fun `exactly 30 percent passes`() {
        val margin = StressTestedMargin(Percentage.of(30.0))
        assertEquals(Percentage.of(30.0), margin.value)
    }

    @Test
    fun `above 30 percent passes`() {
        val margin = StressTestedMargin(Percentage.of(55.0))
        assertEquals(Percentage.of(55.0), margin.value)
    }

    @Test
    fun `29_99 percent fails`() {
        assertThrows<IllegalArgumentException> {
            StressTestedMargin(Percentage.of(29.99))
        }
    }

    @Test
    fun `zero percent fails`() {
        assertThrows<IllegalArgumentException> {
            StressTestedMargin(Percentage.of(0.0))
        }
    }
}
