package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.PortfolioConfig
import com.autoshipper.portfolio.domain.ExperimentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortfolioReporterTest {

    @Mock
    private lateinit var experimentService: ExperimentService

    @Mock
    private lateinit var marginSignalProvider: MarginSignalProvider

    private lateinit var reporter: PortfolioReporter

    @BeforeEach
    fun setUp() {
        val config = PortfolioConfig(kpiCacheTtlMinutes = 5)
        reporter = PortfolioReporter(experimentService, marginSignalProvider, config)

        whenever(experimentService.countByStatus(ExperimentStatus.ACTIVE)).thenReturn(3L)
        whenever(experimentService.countByStatus(ExperimentStatus.VALIDATED)).thenReturn(2L)
        whenever(experimentService.countByStatus(ExperimentStatus.FAILED)).thenReturn(1L)
        whenever(experimentService.countByStatus(ExperimentStatus.LAUNCHED)).thenReturn(4L)
        whenever(experimentService.countByStatus(ExperimentStatus.TERMINATED)).thenReturn(2L)
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(
            listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        )
        whenever(marginSignalProvider.getBlendedNetMargin()).thenReturn(BigDecimal("42.50"))
        whenever(marginSignalProvider.getTotalProfit()).thenReturn(BigDecimal("50000.00"))
    }

    @Test
    fun `summary returns correct aggregate counts`() {
        val summary = reporter.summary()

        assertEquals(12L, summary.totalExperiments) // 3+2+1+4+2
        assertEquals(3L, summary.activeExperiments)
        assertEquals(3, summary.activeSkus)
        assertEquals(2L, summary.terminatedSkus)
        assertEquals(BigDecimal("42.50"), summary.blendedNetMargin)
        assertEquals(BigDecimal("50000.00"), summary.totalProfit)
    }

    @Test
    fun `second call returns cached result without re-querying`() {
        reporter.summary()
        reporter.summary()

        // countByStatus(ACTIVE) is called once per summary computation, not twice
        // If cache works, only the first summary() call should trigger queries
        verify(marginSignalProvider, times(1)).getActiveSkuIds()
        verify(marginSignalProvider, times(1)).getBlendedNetMargin()
    }
}
