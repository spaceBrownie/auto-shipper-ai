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
class RefundPatternAnalyzerTest {

    @Mock
    private lateinit var marginSignalProvider: MarginSignalProvider

    private lateinit var analyzer: RefundPatternAnalyzer

    private val sku1 = UUID.randomUUID()
    private val sku2 = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        analyzer = RefundPatternAnalyzer(marginSignalProvider)
    }

    @Test
    fun `identifies SKUs with elevated refund rates`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(sku1, sku2))
        whenever(marginSignalProvider.getPortfolioAverageRefundRate()).thenReturn(BigDecimal("2.0"))
        whenever(marginSignalProvider.getAverageRefundRate(sku1)).thenReturn(BigDecimal("4.5")) // above 3% threshold and portfolio avg
        whenever(marginSignalProvider.getAverageRefundRate(sku2)).thenReturn(BigDecimal("1.5")) // below threshold

        val alert = analyzer.analyze()

        assertEquals(1, alert.elevatedSkuCount)
        assertTrue(alert.skuIds.contains(sku1))
    }

    @Test
    fun `no elevated refund rates returns empty`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(sku1))
        whenever(marginSignalProvider.getPortfolioAverageRefundRate()).thenReturn(BigDecimal("1.0"))
        whenever(marginSignalProvider.getAverageRefundRate(sku1)).thenReturn(BigDecimal("1.0"))

        val alert = analyzer.analyze()

        assertEquals(0, alert.elevatedSkuCount)
        assertTrue(alert.skuIds.isEmpty())
    }

    @Test
    fun `no active SKUs returns empty alert`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(emptyList())
        whenever(marginSignalProvider.getPortfolioAverageRefundRate()).thenReturn(BigDecimal.ZERO)

        val alert = analyzer.analyze()

        assertEquals(0, alert.elevatedSkuCount)
        assertTrue(alert.skuIds.isEmpty())
    }
}
