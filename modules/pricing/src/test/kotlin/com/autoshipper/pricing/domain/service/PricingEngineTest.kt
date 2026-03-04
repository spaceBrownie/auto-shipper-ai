package com.autoshipper.pricing.domain.service

import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.pricing.config.PricingConfig
import com.autoshipper.pricing.persistence.SkuPriceEntity
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.pricing.persistence.SkuPricingHistoryEntity
import com.autoshipper.pricing.persistence.SkuPricingHistoryRepository
import com.autoshipper.shared.events.PricingDecision
import com.autoshipper.shared.events.PricingSignal
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class PricingEngineTest {

    @Mock lateinit var skuPriceRepository: SkuPriceRepository
    @Mock lateinit var costEnvelopeRepository: CostEnvelopeRepository
    @Mock lateinit var pricingHistoryRepository: SkuPricingHistoryRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    @Captor lateinit var decisionCaptor: ArgumentCaptor<Any>

    private lateinit var engine: PricingEngine

    private val skuId = SkuId.new()
    private val config = PricingConfig(
        marginFloorPercent = BigDecimal("30"),
        conversionThresholdPercent = BigDecimal("15"),
        maxPriceMultiplier = BigDecimal("2.0")
    )

    private fun usd(amount: Double) = Money.of(amount, Currency.USD)

    @BeforeEach
    fun setUp() {
        engine = PricingEngine(
            skuPriceRepository = skuPriceRepository,
            costEnvelopeRepository = costEnvelopeRepository,
            pricingHistoryRepository = pricingHistoryRepository,
            pricingConfig = config,
            eventPublisher = eventPublisher
        )
    }

    private fun priceEntity(price: Double, margin: Double) = SkuPriceEntity(
        skuId = skuId.value,
        currency = "USD",
        currentPriceAmount = BigDecimal.valueOf(price),
        currentMarginPercent = BigDecimal.valueOf(margin)
    )

    private fun costEnvelopeEntity(supplierCost: Double): CostEnvelopeEntity {
        val zero = BigDecimal.ZERO
        return CostEnvelopeEntity(
            skuId = skuId.value,
            currency = "USD",
            supplierUnitCostAmount = BigDecimal.valueOf(supplierCost),
            inboundShippingAmount = zero,
            outboundShippingAmount = zero,
            platformFeeAmount = zero,
            processingFeeAmount = zero,
            packagingCostAmount = zero,
            returnHandlingCostAmount = zero,
            customerAcquisitionCostAmount = zero,
            warehousingCostAmount = zero,
            customerServiceCostAmount = zero,
            refundAllowanceAmount = zero,
            chargebackAllowanceAmount = zero,
            taxesAndDutiesAmount = zero,
            verifiedAt = Instant.now()
        )
    }

    @Test
    fun `emits Adjusted when margin remains above floor after shipping cost increase`() {
        // Price = 100, cost = 40, margin = 60%. Delta +5 → cost = 45, margin = 55% (still > 30%)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.ShippingCostChanged(skuId, usd(5.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.Adjusted
        assertEquals(skuId, decision.skuId)
        assertEquals(usd(100.0), decision.newPrice)
    }

    @Test
    fun `emits Adjusted with new price when cost increase breaches margin but price increase within conversion threshold`() {
        // Price = 100, cost = 65, margin = 35%. Delta +10 → cost = 75, margin = 25% (< 30%)
        // Min viable price = 75 / 0.70 = 107.14 → increase = 7.14% (< 15% threshold)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 35.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(65.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.VendorCostChanged(skuId, usd(10.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.Adjusted
        assertEquals(skuId, decision.skuId)
        // New price should be the minimum viable price
        assertTrue(decision.newPrice.normalizedAmount > BigDecimal.valueOf(100.0))
    }

    @Test
    fun `emits PauseRequired when price increase exceeds conversion threshold`() {
        // Price = 100, cost = 65, margin = 35%. Delta +20 → cost = 85, margin = 15% (< 30%)
        // Min viable price = 85 / 0.70 = 121.43 → increase = 21.43% (> 15% threshold)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 35.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(65.0))
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.CacChanged(skuId, usd(20.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.PauseRequired
        assertEquals(skuId, decision.skuId)
        assertTrue(decision.reason.contains("conversion threshold"))
    }

    @Test
    fun `emits TerminateRequired when min viable price exceeds max price multiplier`() {
        // Price = 50, cost = 45. Delta +30 → cost = 75
        // Min viable price = 75 / 0.70 = 107.14
        // Max acceptable = 50 * 2.0 = 100 → 107.14 > 100 → structurally dead
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(50.0, 10.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(45.0))
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.PlatformFeeChanged(skuId, usd(30.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.TerminateRequired
        assertEquals(skuId, decision.skuId)
    }

    @Test
    fun `emits PauseRequired when min viable price within multiplier but exceeds conversion threshold`() {
        // Price = 100, cost = 65. Delta +20 → cost = 85
        // Min viable price = 85 / 0.70 = 121.43 → within 2x cap (200)
        // Price increase = 21.43% > 15% threshold → PauseRequired
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 35.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(65.0))
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.PlatformFeeChanged(skuId, usd(20.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.PauseRequired
        assertTrue(decision.reason.contains("conversion threshold"))
    }

    @Test
    fun `ignores signal when no price record exists for SKU`() {
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(null)

        val signal = PricingSignal.ShippingCostChanged(skuId, usd(5.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `ignores signal when no cost envelope exists for SKU`() {
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(null)

        val signal = PricingSignal.ShippingCostChanged(skuId, usd(5.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `all four signal types are processed correctly`() {
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signals = listOf(
            PricingSignal.ShippingCostChanged(skuId, usd(1.0)),
            PricingSignal.VendorCostChanged(skuId, usd(1.0)),
            PricingSignal.CacChanged(skuId, usd(1.0)),
            PricingSignal.PlatformFeeChanged(skuId, usd(1.0))
        )

        signals.forEach { signal -> engine.onPricingSignal(signal) }

        verify(eventPublisher, times(4)).publishEvent(argThat<PricingDecision> { this is PricingDecision.Adjusted })
    }

    @Test
    fun `margin exactly at 30 percent floor emits Adjusted`() {
        // Price = 100, cost = 70 → margin = 30% exactly at floor
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 30.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(70.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        // Delta of 0 (no change) — margin stays at exactly 30%
        val signal = PricingSignal.ShippingCostChanged(skuId, usd(0.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        assertTrue(decisionCaptor.value is PricingDecision.Adjusted)
    }

    @Test
    fun `margin at 29_9 percent emits PauseRequired or price adjustment`() {
        // Price = 100, cost = 70. Delta +0.10 → cost = 70.10, margin = 29.9% (< 30%)
        // Min viable = 70.10 / 0.70 = 100.14 → increase = 0.14% (< 15%)
        // So this should emit Adjusted with new price
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 30.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(70.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.ShippingCostChanged(skuId, usd(0.10))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.Adjusted
        // Price should be slightly above 100
        assertTrue(decision.newPrice.normalizedAmount >= BigDecimal.valueOf(100.0))
    }

    @Test
    fun `persists pricing history on every decision`() {
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(5.0)))

        verify(pricingHistoryRepository).save(argThat<SkuPricingHistoryEntity> {
            this.signalType == "SHIPPING_COST_CHANGED" && this.decisionType == "ADJUSTED"
        })
    }

    @Test
    fun `history records effective margin against adjusted price, not stale old-price margin`() {
        // Price = 100, cost = 65. Delta +10 → cost = 75, margin vs old price = 25% (below floor)
        // Min viable price = 75 / 0.70 = 107.1429 → increase = 7.14% (< 15% threshold)
        // Adjusted price = 107.1429, effective margin = (107.1429 - 75) / 107.1429 = 30%
        // History should record ~30% margin, NOT the stale 25%
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 35.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(65.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.VendorCostChanged(skuId, usd(10.0)))

        verify(pricingHistoryRepository).save(argThat<SkuPricingHistoryEntity> {
            // Effective margin should be ~30% (at the floor), not 25% (stale old-price margin)
            this.marginPercent >= BigDecimal("29.9") && this.marginPercent <= BigDecimal("30.1")
        })
    }

    @Test
    fun `emits Adjusted when cost decrease makes fully burdened negative`() {
        // Price = 100, cost = 40. Delta -50 → cost = -10 (negative fully burdened)
        // Negative cost means margin is above any floor — should emit Adjusted, not crash
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.ShippingCostChanged(skuId, usd(-50.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.Adjusted
        assertEquals(skuId, decision.skuId)
        assertEquals(usd(100.0), decision.newPrice)
    }

    @Test
    fun `emits Adjusted when cost decrease makes fully burdened zero`() {
        // Price = 100, cost = 40. Delta -40 → cost = 0
        // Zero cost means margin is above any floor — should emit Adjusted
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        val signal = PricingSignal.VendorCostChanged(skuId, usd(-40.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value as PricingDecision.Adjusted
        assertEquals(skuId, decision.skuId)
        assertEquals(usd(100.0), decision.newPrice)
    }

    @Test
    fun `history records 100 percent margin when cost goes negative`() {
        // Price = 100, cost = 40. Delta -50 → cost = -10
        // safeMargin should return 100% for negative cost
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(-50.0)))

        verify(pricingHistoryRepository).save(argThat<SkuPricingHistoryEntity> {
            this.marginPercent.compareTo(BigDecimal("100")) == 0
        })
    }

    @Test
    fun `history records unchanged margin when price stays the same`() {
        // Price = 100, cost = 40. Delta +5 → cost = 45, margin = 55%
        // Price stays at 100, margin against 100 = 55% — should be recorded as-is
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(100.0, 60.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(5.0)))

        verify(pricingHistoryRepository).save(argThat<SkuPricingHistoryEntity> {
            this.marginPercent.compareTo(BigDecimal("55.0000")) == 0
        })
    }

    // --- Cumulative cost drift tests ---

    @Test
    fun `sequential signals accumulate fully burdened cost`() {
        // Price = 100, envelope cost = 40. Signal 1: +5 → running = 45. Signal 2: +3 → running = 48.
        // Without the fix, signal 2 would see 40+3=43 instead of 45+3=48.
        val entity = priceEntity(100.0, 60.0)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(5.0)))

        // After signal 1: running total should be 45
        assertEquals(0, BigDecimal("45.0000").compareTo(entity.currentFullyBurdenedAmount),
            "After first signal, running total should be 45")

        engine.onPricingSignal(PricingSignal.VendorCostChanged(skuId, usd(3.0)))

        // After signal 2: running total should be 48 (45+3), not 43 (40+3)
        assertEquals(0, BigDecimal("48.0000").compareTo(entity.currentFullyBurdenedAmount),
            "After second signal, running total should be 48 (cumulative)")
        // Margin = (100-48)/100 = 52%
        assertEquals(0, BigDecimal("52.0000").compareTo(entity.currentMarginPercent),
            "Margin should reflect cumulative cost of 48 against price 100")
    }

    @Test
    fun `first signal falls back to envelope when no running total exists`() {
        // Entity has null currentFullyBurdenedAmount → falls back to computeFullyBurdened(envelope)
        val entity = priceEntity(100.0, 60.0)
        assertNull(entity.currentFullyBurdenedAmount, "Fresh entity should have null running total")

        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(5.0)))

        // After first signal, running total is initialized from envelope (40) + delta (5) = 45
        assertNotNull(entity.currentFullyBurdenedAmount)
        assertEquals(0, BigDecimal("45.0000").compareTo(entity.currentFullyBurdenedAmount))
    }

    @Test
    fun `persists running fully burdened amount when price is adjusted upward`() {
        // Price = 100, cost = 65. Delta +10 → cost = 75 → margin breached → price bumped
        val entity = priceEntity(100.0, 35.0)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(65.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        engine.onPricingSignal(PricingSignal.VendorCostChanged(skuId, usd(10.0)))

        // Running total should be 75 even after price adjustment
        assertEquals(0, BigDecimal("75.0000").compareTo(entity.currentFullyBurdenedAmount),
            "Running total should persist even when price is adjusted")
    }

    @Test
    fun `cumulative signals that cross margin floor trigger price adjustment`() {
        // Price = 100, envelope cost = 40.
        // Signal 1: +20 → cost = 60, margin = 40% (ok)
        // Signal 2: +15 → cost = 75, margin = 25% (breaches 30% floor → price bump)
        // Without cumulative tracking, signal 2 would see 40+15=55, margin 45% (incorrectly fine)
        val entity = priceEntity(100.0, 60.0)
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(40.0))
        whenever(skuPriceRepository.save(any<SkuPriceEntity>())).thenAnswer { it.arguments[0] }
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        // Signal 1: +20 → cost = 60, margin = 40% → Adjusted (price stays 100)
        engine.onPricingSignal(PricingSignal.ShippingCostChanged(skuId, usd(20.0)))
        verify(eventPublisher).publishEvent(argThat<PricingDecision> { this is PricingDecision.Adjusted })

        // Signal 2: +15 → cost = 75, margin = 25% < 30% → should bump price
        engine.onPricingSignal(PricingSignal.VendorCostChanged(skuId, usd(15.0)))

        // The second decision should be Adjusted with a price > 100 (min viable price)
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher, times(2)).publishEvent(captor.capture())
        val secondDecision = captor.allValues[1] as PricingDecision.Adjusted
        assertTrue(secondDecision.newPrice.normalizedAmount > BigDecimal("100"),
            "Second signal should trigger price bump due to cumulative cost of 75")
    }

    // --- Optimistic locking tests ---

    @Test
    fun `SkuPriceEntity has version field for optimistic locking`() {
        val entity = priceEntity(100.0, 60.0)
        assertEquals(0L, entity.version, "New entity should have version 0")
    }
}
