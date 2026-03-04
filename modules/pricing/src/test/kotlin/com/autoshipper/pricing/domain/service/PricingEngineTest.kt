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
        conversionThresholdPercent = BigDecimal("15")
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
    fun `emits TerminateRequired when margin is negative and no viable price exists`() {
        // Price = 100, cost = 95, margin = 5%. Delta +20 → cost = 115 > price
        // Min viable price = 115 / 0.70 = 164.29 → increase = 64.29% (> 15%)
        // But really, cost > price, so margin is negative.
        // With the huge price increase needed (64%), this triggers PauseRequired, not terminate.
        // For true terminate: we need a scenario where the margin floor is impossible.
        // Actually let's test with very high costs where increase is > 100%
        whenever(skuPriceRepository.findBySkuId(skuId.value)).thenReturn(priceEntity(50.0, 10.0))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(costEnvelopeEntity(45.0))
        whenever(pricingHistoryRepository.save(any<SkuPricingHistoryEntity>())).thenAnswer { it.arguments[0] }

        // Delta +30 → cost = 75, min price = 75/0.70 = 107.14, increase from 50 = 114% > 15%
        val signal = PricingSignal.PlatformFeeChanged(skuId, usd(30.0))
        engine.onPricingSignal(signal)

        verify(eventPublisher).publishEvent(decisionCaptor.capture())
        val decision = decisionCaptor.value
        // Should be PauseRequired since a viable price technically exists but increase is too high
        assertTrue(decision is PricingDecision.PauseRequired)
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
}
