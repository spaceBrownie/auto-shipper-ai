package com.autoshipper.capital.domain.service

import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuPnlReporterTest {

    @Mock
    private lateinit var snapshotRepository: MarginSnapshotRepository

    private lateinit var reporter: SkuPnlReporter

    private val skuUuid = UUID.randomUUID()
    private val skuId = SkuId(skuUuid)
    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2026, 1, 31)

    @BeforeEach
    fun setUp() {
        reporter = SkuPnlReporter(snapshotRepository)
    }

    @Test
    fun `aggregates revenue and cost correctly from multiple snapshots`() {
        val snapshots = listOf(
            makeSnapshot(revenue = BigDecimal("100"), cost = BigDecimal("40")),
            makeSnapshot(revenue = BigDecimal("200"), cost = BigDecimal("80")),
            makeSnapshot(revenue = BigDecimal("150"), cost = BigDecimal("60"))
        )
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            eq(skuUuid), eq(from), eq(to)
        )).thenReturn(snapshots)

        val report = reporter.report(skuId, from, to)

        assertEquals(Money.of(BigDecimal("450"), Currency.USD), report.totalRevenue)
        assertEquals(Money.of(BigDecimal("180"), Currency.USD), report.totalCost)
    }

    @Test
    fun `computes average gross and net margin correctly`() {
        val snapshots = listOf(
            makeSnapshot(grossMargin = BigDecimal("50"), netMargin = BigDecimal("30")),
            makeSnapshot(grossMargin = BigDecimal("60"), netMargin = BigDecimal("40")),
            makeSnapshot(grossMargin = BigDecimal("55"), netMargin = BigDecimal("35"))
        )
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            eq(skuUuid), eq(from), eq(to)
        )).thenReturn(snapshots)

        val report = reporter.report(skuId, from, to)

        assertEquals(0, BigDecimal("55.00").compareTo(report.averageGrossMarginPercent))
        assertEquals(0, BigDecimal("35.00").compareTo(report.averageNetMarginPercent))
    }

    @Test
    fun `returns zero report for empty snapshots`() {
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            eq(skuUuid), eq(from), eq(to)
        )).thenReturn(emptyList())

        val report = reporter.report(skuId, from, to)

        assertEquals(Money.of(BigDecimal.ZERO, Currency.USD), report.totalRevenue)
        assertEquals(Money.of(BigDecimal.ZERO, Currency.USD), report.totalCost)
        assertEquals(0, BigDecimal.ZERO.compareTo(report.averageGrossMarginPercent))
        assertEquals(0, BigDecimal.ZERO.compareTo(report.averageNetMarginPercent))
        assertEquals(0, report.snapshotCount)
    }

    @Test
    fun `returns correct snapshot count`() {
        val snapshots = listOf(
            makeSnapshot(), makeSnapshot(), makeSnapshot(), makeSnapshot()
        )
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            eq(skuUuid), eq(from), eq(to)
        )).thenReturn(snapshots)

        val report = reporter.report(skuId, from, to)

        assertEquals(4, report.snapshotCount)
    }

    private fun makeSnapshot(
        revenue: BigDecimal = BigDecimal("100.0000"),
        cost: BigDecimal = BigDecimal("50.0000"),
        grossMargin: BigDecimal = BigDecimal("50.00"),
        netMargin: BigDecimal = BigDecimal("35.00")
    ): MarginSnapshot {
        return MarginSnapshot(
            skuId = skuUuid,
            snapshotDate = LocalDate.now(),
            grossMargin = grossMargin,
            netMargin = netMargin,
            revenueAmount = revenue,
            revenueCurrency = Currency.USD,
            totalCostAmount = cost,
            totalCostCurrency = Currency.USD,
            refundRate = BigDecimal("1.00"),
            chargebackRate = BigDecimal("0.50"),
            cacVariance = BigDecimal("5.00")
        )
    }
}
