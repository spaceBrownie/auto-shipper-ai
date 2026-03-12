package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.CapitalRuleAuditRepository
import com.autoshipper.shared.events.PricingSignal
import com.autoshipper.shared.events.ShutdownRuleTriggered
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShutdownRuleEngineTest {

    @Mock
    private lateinit var auditRepository: CapitalRuleAuditRepository

    @Mock
    private lateinit var orderRecordRepository: CapitalOrderRecordRepository

    @Mock
    private lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var engine: ShutdownRuleEngine

    private val skuId = SkuId(UUID.randomUUID())

    private val capitalConfig = CapitalConfig(
        netMarginFloorPercent = BigDecimal("30"),
        sustainedDays = 7,
        refundRateMaxPercent = BigDecimal("5"),
        chargebackRateMaxPercent = BigDecimal("2"),
        cacVarianceMaxPercent = BigDecimal("15"),
        cacVarianceDays = 14,
        reserveRateMinPercent = BigDecimal("10"),
        reserveRateMaxPercent = BigDecimal("15")
    )

    @BeforeEach
    fun setUp() {
        engine = ShutdownRuleEngine(capitalConfig, auditRepository, orderRecordRepository, eventPublisher)
        whenever(auditRepository.save(any())).thenAnswer { it.arguments[0] }
        // Default: no orders (prevents refund/chargeback rules from firing)
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(0L)
    }

    // --- Margin Breach Tests ---

    @Test
    fun `6 consecutive below-30 percent margin days does not trigger shutdown`() {
        val snapshots = (0 until 6).map { i ->
            makeSnapshot(netMargin = BigDecimal("25"), daysAgo = i.toLong())
        }

        engine.evaluate(skuId, snapshots)

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `7 consecutive below-30 percent margin days triggers shutdown with PAUSE action`() {
        val snapshots = (0 until 7).map { i ->
            makeSnapshot(netMargin = BigDecimal("25"), daysAgo = i.toLong())
        }

        engine.evaluate(skuId, snapshots)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        val event = captor.value as ShutdownRuleTriggered
        assertEquals("MARGIN_BREACH", event.rule)
        assertEquals("PAUSE", event.action)
    }

    @Test
    fun `7 days with some above 30 percent does not trigger margin breach`() {
        val snapshots = (0 until 7).map { i ->
            val margin = if (i == 3) BigDecimal("35") else BigDecimal("25")
            makeSnapshot(netMargin = margin, daysAgo = i.toLong())
        }

        engine.evaluate(skuId, snapshots)

        verify(eventPublisher, never()).publishEvent(any())
    }

    // --- Refund Rate Tests ---

    @Test
    fun `refund rate at 4 point 9 percent does not trigger shutdown`() {
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(1000L)
        whenever(orderRecordRepository.countBySkuIdAndRefundedTrueAndRecordedAtAfter(any(), any())).thenReturn(49L)
        whenever(orderRecordRepository.countBySkuIdAndChargebackedTrueAndRecordedAtAfter(any(), any())).thenReturn(0L)

        engine.evaluate(skuId, emptyList())

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `refund rate at 5 point 1 percent triggers shutdown with PAUSE action`() {
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(1000L)
        whenever(orderRecordRepository.countBySkuIdAndRefundedTrueAndRecordedAtAfter(any(), any())).thenReturn(51L)
        whenever(orderRecordRepository.countBySkuIdAndChargebackedTrueAndRecordedAtAfter(any(), any())).thenReturn(0L)

        engine.evaluate(skuId, emptyList())

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        val event = captor.value as ShutdownRuleTriggered
        assertEquals("REFUND_RATE_BREACH", event.rule)
        assertEquals("PAUSE", event.action)
    }

    // --- Chargeback Rate Tests ---

    @Test
    fun `chargeback rate at 1 point 9 percent does not trigger shutdown`() {
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(1000L)
        whenever(orderRecordRepository.countBySkuIdAndRefundedTrueAndRecordedAtAfter(any(), any())).thenReturn(0L)
        whenever(orderRecordRepository.countBySkuIdAndChargebackedTrueAndRecordedAtAfter(any(), any())).thenReturn(19L)

        engine.evaluate(skuId, emptyList())

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `chargeback rate at 2 point 1 percent triggers shutdown with PAUSE_COMPLIANCE action`() {
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(1000L)
        whenever(orderRecordRepository.countBySkuIdAndRefundedTrueAndRecordedAtAfter(any(), any())).thenReturn(0L)
        whenever(orderRecordRepository.countBySkuIdAndChargebackedTrueAndRecordedAtAfter(any(), any())).thenReturn(21L)

        engine.evaluate(skuId, emptyList())

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        val event = captor.value as ShutdownRuleTriggered
        assertEquals("CHARGEBACK_RATE_BREACH", event.rule)
        assertEquals("PAUSE_COMPLIANCE", event.action)
    }

    // --- CAC Instability Tests ---

    @Test
    fun `CAC variance at 14 percent does not trigger action`() {
        val snapshots = listOf(
            makeSnapshot(cacVariance = BigDecimal("14"), daysAgo = 0)
        )

        engine.evaluate(skuId, snapshots)

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `CAC variance at 16 percent publishes PricingSignal CacChanged not ShutdownRuleTriggered`() {
        val snapshots = listOf(
            makeSnapshot(cacVariance = BigDecimal("16"), daysAgo = 0)
        )

        engine.evaluate(skuId, snapshots)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())

        val event = captor.value
        assert(event is PricingSignal.CacChanged) { "Expected PricingSignal.CacChanged, got ${event::class}" }
        assertEquals(skuId, (event as PricingSignal.CacChanged).skuId)
    }

    // --- Edge cases ---

    @Test
    fun `no orders avoids division by zero for refund and chargeback`() {
        engine.evaluate(skuId, emptyList())

        verify(eventPublisher, never()).publishEvent(any())
    }

    // --- Helpers ---

    private fun makeSnapshot(
        netMargin: BigDecimal = BigDecimal("40"),
        grossMargin: BigDecimal = BigDecimal("55"),
        cacVariance: BigDecimal = BigDecimal("5"),
        daysAgo: Long = 0
    ): MarginSnapshot {
        return MarginSnapshot(
            skuId = skuId.value,
            snapshotDate = LocalDate.now().minusDays(daysAgo),
            grossMargin = grossMargin,
            netMargin = netMargin,
            revenueAmount = BigDecimal("100.0000"),
            revenueCurrency = Currency.USD,
            totalCostAmount = BigDecimal("60.0000"),
            totalCostCurrency = Currency.USD,
            refundRate = BigDecimal("1.00"),
            chargebackRate = BigDecimal("0.50"),
            cacVariance = cacVariance
        )
    }
}
