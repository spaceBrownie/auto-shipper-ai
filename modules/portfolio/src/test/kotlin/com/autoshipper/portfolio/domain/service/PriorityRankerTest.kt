package com.autoshipper.portfolio.domain.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriorityRankerTest {

    @Mock
    private lateinit var marginSignalProvider: MarginSignalProvider

    private lateinit var ranker: PriorityRanker

    private val sku1 = UUID.randomUUID()
    private val sku2 = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        ranker = PriorityRanker(marginSignalProvider)
    }

    @Test
    fun `SKUs ranked by risk-adjusted return descending`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(sku1, sku2))

        // SKU1: high margin, medium revenue, low risk
        whenever(marginSignalProvider.getAverageNetMargin(sku1)).thenReturn(BigDecimal("40"))
        whenever(marginSignalProvider.getAverageRevenueVolume(sku1)).thenReturn(BigDecimal("1000"))
        whenever(marginSignalProvider.getAverageRefundRate(sku1)).thenReturn(BigDecimal("1.0"))

        // SKU2: lower margin, lower revenue, higher risk
        whenever(marginSignalProvider.getAverageNetMargin(sku2)).thenReturn(BigDecimal("20"))
        whenever(marginSignalProvider.getAverageRevenueVolume(sku2)).thenReturn(BigDecimal("500"))
        whenever(marginSignalProvider.getAverageRefundRate(sku2)).thenReturn(BigDecimal("4.0"))

        val rankings = ranker.rank()

        assertEquals(2, rankings.size)
        assertEquals(sku1, rankings[0].skuId)
        assertEquals(sku2, rankings[1].skuId)
        assertTrue(rankings[0].riskAdjustedReturn > rankings[1].riskAdjustedReturn)
    }

    @Test
    fun `empty active SKUs returns empty list`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(emptyList())

        val rankings = ranker.rank()

        assertTrue(rankings.isEmpty())
    }

    @Test
    fun `risk factor includes refund rate`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(sku1))
        whenever(marginSignalProvider.getAverageNetMargin(sku1)).thenReturn(BigDecimal("50"))
        whenever(marginSignalProvider.getAverageRevenueVolume(sku1)).thenReturn(BigDecimal("100"))
        whenever(marginSignalProvider.getAverageRefundRate(sku1)).thenReturn(BigDecimal("5.0"))

        val rankings = ranker.rank()

        assertEquals(1, rankings.size)
        // Risk factor = 1 + 5/100 = 1.05
        assertEquals(0, BigDecimal("1.0500").compareTo(rankings[0].riskFactor))
    }
}
