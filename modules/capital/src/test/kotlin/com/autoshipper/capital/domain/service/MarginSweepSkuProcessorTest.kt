package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.CapitalRuleAuditRepository
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.shared.events.MarginSnapshotTaken
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarginSweepSkuProcessorTest {

    @Mock
    private lateinit var snapshotRepository: MarginSnapshotRepository

    @Mock
    private lateinit var orderRecordRepository: CapitalOrderRecordRepository

    @Mock
    private lateinit var auditRepository: CapitalRuleAuditRepository

    @Mock
    private lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var processor: MarginSweepSkuProcessor

    private val skuId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 3, 10)

    @BeforeEach
    fun setUp() {
        // Use a real ShutdownRuleEngine (with no-op behavior for non-matching conditions)
        // to avoid Mockito issues with @JvmInline value class SkuId parameters
        val capitalConfig = CapitalConfig(
            netMarginFloorPercent = BigDecimal("30"),
            sustainedDays = 7,
            refundRateMaxPercent = BigDecimal("5"),
            chargebackRateMaxPercent = BigDecimal("2"),
            cacVarianceMaxPercent = BigDecimal("15"),
            cacVarianceDays = 14,
            reserveRateMinPercent = BigDecimal("10"),
            reserveRateMaxPercent = BigDecimal("15")
        )
        val shutdownRuleEngine = ShutdownRuleEngine(
            capitalConfig, auditRepository, orderRecordRepository, eventPublisher
        )

        processor = MarginSweepSkuProcessor(
            snapshotRepository, orderRecordRepository, shutdownRuleEngine, eventPublisher
        )
        whenever(snapshotRepository.save(any<MarginSnapshot>())).thenAnswer { it.arguments[0] }
        whenever(snapshotRepository.findRecentBySkuId(any(), any())).thenReturn(emptyList())
        // Default: no orders (prevents refund/chargeback rules from firing in ShutdownRuleEngine)
        whenever(orderRecordRepository.countBySkuIdAndRecordedAtAfter(any(), any())).thenReturn(0L)
    }

    @Test
    fun `creates snapshot and evaluates rules on first run of the day`() {
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId, today, today
        )).thenReturn(emptyList())

        stubOrdersForSku()

        processor.process(skuId, today)

        verify(snapshotRepository).save(any<MarginSnapshot>())
        // findRecentBySkuId called for both cacVariance computation and rule evaluation
        verify(snapshotRepository, times(2)).findRecentBySkuId(any(), any())
    }

    @Test
    fun `skips snapshot creation but still evaluates rules when snapshot already exists`() {
        val existingSnapshot = makeSnapshot()
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId, today, today
        )).thenReturn(listOf(existingSnapshot))

        processor.process(skuId, today)

        verify(snapshotRepository, never()).save(any<MarginSnapshot>())
        // findRecentBySkuId called once for rule evaluation (no cacVariance computation)
        verify(snapshotRepository).findRecentBySkuId(eq(skuId), any())
    }

    @Test
    fun `evaluates rules even when no orders exist and no snapshot is created`() {
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId, today, today
        )).thenReturn(emptyList())

        whenever(orderRecordRepository.findBySkuIdAndRecordedAtBetween(
            eq(skuId), any(), any()
        )).thenReturn(emptyList())

        processor.process(skuId, today)

        verify(snapshotRepository, never()).save(any<MarginSnapshot>())
        // Rules still evaluated: findRecentBySkuId called for rule evaluation
        verify(snapshotRepository).findRecentBySkuId(eq(skuId), any())
    }

    @Test
    fun `publishes MarginSnapshotTaken event when snapshot is created`() {
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId, today, today
        )).thenReturn(emptyList())

        stubOrdersForSku()

        processor.process(skuId, today)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        val event = captor.value as MarginSnapshotTaken
        assertEquals(SkuId(skuId), event.skuId)
    }

    @Test
    fun `does not publish event when snapshot already exists`() {
        val existingSnapshot = makeSnapshot()
        whenever(snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId, today, today
        )).thenReturn(listOf(existingSnapshot))

        processor.process(skuId, today)

        verify(eventPublisher, never()).publishEvent(any())
    }

    private fun stubOrdersForSku() {
        val order = CapitalOrderRecord(
            orderId = UUID.randomUUID(),
            skuId = skuId,
            totalAmount = BigDecimal("100.0000"),
            currency = Currency.USD,
            status = "FULFILLED",
            refunded = false,
            chargebacked = false,
            recordedAt = Instant.now()
        )
        whenever(orderRecordRepository.findBySkuIdAndRecordedAtBetween(
            eq(skuId), any(), any()
        )).thenReturn(listOf(order))
    }

    private fun makeSnapshot(): MarginSnapshot {
        return MarginSnapshot(
            skuId = skuId,
            snapshotDate = today,
            grossMargin = BigDecimal("50.00"),
            netMargin = BigDecimal("40.00"),
            revenueAmount = BigDecimal("100.0000"),
            revenueCurrency = Currency.USD,
            totalCostAmount = BigDecimal("50.0000"),
            totalCostCurrency = Currency.USD,
            refundRate = BigDecimal("1.00"),
            chargebackRate = BigDecimal("0.50"),
            cacVariance = BigDecimal("5.00")
        )
    }
}
