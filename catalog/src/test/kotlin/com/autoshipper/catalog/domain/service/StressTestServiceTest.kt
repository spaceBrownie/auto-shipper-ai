package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.*
import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.catalog.persistence.StressTestResultEntity
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.shared.events.SkuTerminated
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class StressTestServiceTest {

    @Mock lateinit var skuService: SkuService
    @Mock lateinit var skuRepository: SkuRepository
    @Mock lateinit var costEnvelopeRepository: CostEnvelopeRepository
    @Mock lateinit var stressTestResultRepository: StressTestResultRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private val config = StressTestConfig(
        shippingMultiplier = BigDecimal("2.0"),
        cacIncreasePercent = BigDecimal("15"),
        supplierIncreasePercent = BigDecimal("10"),
        refundRatePercent = BigDecimal("5"),
        chargebackRatePercent = BigDecimal("2"),
        grossMarginFloorPercent = BigDecimal("50"),
        netMarginFloorPercent = BigDecimal("30")
    )

    private lateinit var service: StressTestService

    private val skuId = SkuId.new()

    @BeforeEach
    fun setUp() {
        service = StressTestService(
            skuService = skuService,
            skuRepository = skuRepository,
            costEnvelopeRepository = costEnvelopeRepository,
            stressTestResultRepository = stressTestResultRepository,
            config = config,
            eventPublisher = eventPublisher
        )
    }

    private fun buildEnvelopeEntity(
        supplierUnitCost: Double = 5.0,
        outboundShipping: Double = 3.0,
        cac: Double = 4.0,
        refundAllowance: Double = 1.0,
        chargebackAllowance: Double = 0.5,
        platformFee: Double = 1.0,
        processingFee: Double = 0.5,
        packagingCost: Double = 0.25,
        returnHandlingCost: Double = 0.25,
        warehousingCost: Double = 0.5,
        customerServiceCost: Double = 0.25,
        taxesAndDuties: Double = 0.5,
        inboundShipping: Double = 0.0
    ): CostEnvelopeEntity {
        return CostEnvelopeEntity(
            id = UUID.randomUUID(),
            skuId = skuId.value,
            currency = "USD",
            supplierUnitCostAmount = BigDecimal.valueOf(supplierUnitCost),
            inboundShippingAmount = BigDecimal.valueOf(inboundShipping),
            outboundShippingAmount = BigDecimal.valueOf(outboundShipping),
            platformFeeAmount = BigDecimal.valueOf(platformFee),
            processingFeeAmount = BigDecimal.valueOf(processingFee),
            packagingCostAmount = BigDecimal.valueOf(packagingCost),
            returnHandlingCostAmount = BigDecimal.valueOf(returnHandlingCost),
            customerAcquisitionCostAmount = BigDecimal.valueOf(cac),
            warehousingCostAmount = BigDecimal.valueOf(warehousingCost),
            customerServiceCostAmount = BigDecimal.valueOf(customerServiceCost),
            refundAllowanceAmount = BigDecimal.valueOf(refundAllowance),
            chargebackAllowanceAmount = BigDecimal.valueOf(chargebackAllowance),
            taxesAndDutiesAmount = BigDecimal.valueOf(taxesAndDuties),
            verifiedAt = Instant.now()
        )
    }

    @Test
    fun `happy path - high price produces passing stress test, returns LaunchReadySku`() {
        // Use low costs and high price so margin exceeds 50%
        // Supplier=5, shipping=3, cac=4, refund=1, chargeback=0.5, others=3.25 => total ~16.75
        // After stress: shipping=6, cac=4.6, supplier=5.5, refund=5, chargeback=2
        // stressed total = 16.75 - 3 + 6 - 4 + 4.6 - 5 + 5.5 - 1 + 5 - 0.5 + 2 = 26.35
        // price = 100 => margin = (100 - 26.35) / 100 * 100 = 73.65% — passes
        val envelopeEntity = buildEnvelopeEntity()
        val sku = com.autoshipper.catalog.domain.Sku(
            id = skuId.value, name = "Test SKU", category = "Test",
            currentStateDiscriminator = "LISTED"
        )
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(stressTestResultRepository.save(any<StressTestResultEntity>())).thenAnswer { it.arguments[0] }
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(sku))

        val estimatedPrice = Money.of(100.0, Currency.USD)
        val result = service.run(skuId, estimatedPrice)

        assertNotNull(result)
        assertEquals(sku, result.sku)
        assertTrue(result.stressTestedMargin.value.value >= BigDecimal("30"))
        assertNotNull(result.envelope)
        verify(stressTestResultRepository).save(any())
    }

    @Test
    fun `fail path - low price causes insufficient margin, throws StressTestFailedException and terminates SKU`() {
        // Use same costs but very low price so margin is below 30%
        // With price=15 and same costs, stressed total will exceed price → negative or low margin
        val envelopeEntity = buildEnvelopeEntity()
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(stressTestResultRepository.save(any<StressTestResultEntity>())).thenAnswer { it.arguments[0] }

        val estimatedPrice = Money.of(15.0, Currency.USD)

        assertThrows<StressTestFailedException> {
            service.run(skuId, estimatedPrice)
        }

        verify(stressTestResultRepository).save(any())
        verify(eventPublisher).publishEvent(any<SkuTerminated>())
    }

    @Test
    fun `throws IllegalStateException when no cost envelope exists`() {
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(null)

        assertThrows<IllegalStateException> {
            service.run(skuId, Money.of(100.0, Currency.USD))
        }

        verifyNoInteractions(stressTestResultRepository)
        verifyNoInteractions(skuService)
    }
}
